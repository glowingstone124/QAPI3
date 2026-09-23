package org.qo.services.fallenServices

import com.google.gson.JsonParser
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.FallenTeamDbRepository
import org.qo.services.loginService.Login
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

enum class FallenTeam {
	A,
	B,
	C;

	companion object {
		fun parse(value: String?): FallenTeam? = entries.firstOrNull {
			it.name.equals(value?.trim(), ignoreCase = true)
		}
	}
}

data class FallenTeamSelection(
	val username: String,
	val expectedTeam: FallenTeam,
	val selectedAt: Long,
	val assignedTeam: FallenTeam? = null,
	val assignedAt: Long? = null,
	val activeTeam: FallenTeam = assignedTeam ?: expectedTeam,
) {
	val team: FallenTeam get() = activeTeam
	val finalized: Boolean get() = assignedTeam != null
}

sealed interface FallenSelectionResult {
	data class Selected(val selection: FallenTeamSelection) : FallenSelectionResult
	data class AlreadySelected(val selection: FallenTeamSelection) : FallenSelectionResult
	data object RegistrationClosed : FallenSelectionResult
	data object InvalidTeam : FallenSelectionResult
}

data class FallenRegistration(
	val username: String,
	val expectedTeam: FallenTeam,
	val selectedAt: Long,
	val actualTeam: FallenTeam? = null,
)

@Service
class FallenTeamService @Autowired constructor(
	private val login: Login,
	private val repository: FallenTeamDbRepository,
) {
	constructor(login: Login, database: ReactiveDatabase) : this(login, FallenTeamDbRepository(database))

	private val scope = CoroutineScope(SupervisorJob())
	private val rosterMutex = Mutex()
	@Volatile
	private var rosterCache: FallenRosterCache? = null
	private val assignmentInstant = FallenSchedule.assignmentInstant

	@PreDestroy
	fun shutdown() {
		scope.cancel()
	}

	suspend fun selectionForToken(token: String): Pair<String?, FallenTeamSelection?> {
		val (username, errorCode) = login.validate(token)
		if (username == null || errorCode != 0) return null to null
		return username to selectionForUsername(username)
	}

	suspend fun select(token: String, body: String): Pair<String?, FallenSelectionResult?> {
		val (username, errorCode) = login.validate(token)
		if (username == null || errorCode != 0) return null to null
		if (!Instant.now().isBefore(assignmentInstant)) {
			return username to FallenSelectionResult.RegistrationClosed
		}
		val team = runCatching {
			FallenTeam.parse(JsonParser.parseString(body).asJsonObject.get("team")?.asString)
		}.getOrNull() ?: return username to FallenSelectionResult.InvalidTeam
		return username to selectOnce(username, team)
	}

	suspend fun selectionForUsername(username: String): FallenTeamSelection? {
		repository.ensureSchema()
		finalizeAssignmentsIfDue()
		return repository.read(username)
	}

	suspend fun selectionForJoiningPlayer(username: String): FallenTeamSelection? {
		repository.ensureSchema()
		finalizeAssignmentsIfDue()
		return repository.read(username) ?: if (Instant.now().isBefore(assignmentInstant)) null else repository.assignLatecomer(username)
	}

	suspend fun finalizedRoster(): Map<FallenTeam, List<String>> = readFinalizedRoster()

	@Scheduled(cron = FallenSchedule.ASSIGNMENT_CRON, zone = FallenSchedule.ASSIGNMENT_ZONE_ID)
	fun finalizeScheduledAssignments() {
		scope.launch {
			repository.ensureSchema()
			finalizeAssignmentsIfDue()
		}
	}

	private suspend fun selectOnce(username: String, team: FallenTeam): FallenSelectionResult {
		repository.ensureSchema()
		val selectedAt = System.currentTimeMillis()
		val inserted = repository.insertSelectionIfAbsent(username, team, selectedAt)

		return if (inserted) {
			FallenSelectionResult.Selected(FallenTeamSelection(username, team, selectedAt))
		} else {
			FallenSelectionResult.AlreadySelected(
				requireNotNull(repository.read(username)) { "Fallen team selection disappeared after a duplicate insert" },
			)
		}
	}

	private suspend fun readFinalizedRoster(): Map<FallenTeam, List<String>> {
		val now = System.currentTimeMillis()
		rosterCache?.takeIf { now - it.loadedAt < ROSTER_CACHE_MILLIS }?.let { return it.roster }
		return rosterMutex.withLock {
			val refreshedNow = System.currentTimeMillis()
			rosterCache?.takeIf { refreshedNow - it.loadedAt < ROSTER_CACHE_MILLIS }?.let { return@withLock it.roster }
			repository.ensureSchema()
			finalizeAssignmentsIfDue()
			val roster = repository.readFinalizedRoster()
			rosterCache = FallenRosterCache(roster, refreshedNow)
			roster
		}
	}

	private suspend fun finalizeAssignmentsIfDue() {
		val updated = repository.finalizeAssignmentsIfDue(assignmentInstant)
		if (updated) {
			rosterCache = null
		}
	}

	private data class FallenRosterCache(
		val roster: Map<FallenTeam, List<String>>,
		val loadedAt: Long,
	)

	private companion object {
		const val ROSTER_CACHE_MILLIS = 5_000L
	}
}
