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
import org.qo.services.loginService.Login
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

enum class FallenTeam {
	A, B, C;

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
) {
	val team: FallenTeam get() = assignedTeam ?: expectedTeam
	val finalized: Boolean get() = assignedTeam != null
}

sealed interface FallenSelectionResult {
	data class Selected(val selection: FallenTeamSelection) : FallenSelectionResult
	data class AlreadySelected(val selection: FallenTeamSelection) : FallenSelectionResult
	data object InvalidTeam : FallenSelectionResult
	data object RegistrationClosed : FallenSelectionResult
}

internal data class FallenRegistration(
	val username: String,
	val expectedTeam: FallenTeam,
	val selectedAt: Long,
	val actualTeam: FallenTeam? = null,
)

internal object FallenTeamAllocator {
	fun allocate(registrations: List<FallenRegistration>): Map<String, FallenTeam> {
		if (registrations.isEmpty()) return emptyMap()
		val preferenceCounts = FallenTeam.entries.associateWith { team -> registrations.count { it.expectedTeam == team } }
		val capacities = FallenTeam.entries.associateWith { registrations.size / FallenTeam.entries.size }.toMutableMap()
		FallenTeam.entries
			.sortedWith(compareByDescending<FallenTeam> { preferenceCounts.getValue(it) }.thenBy { it.name })
			.take(registrations.size % FallenTeam.entries.size)
			.forEach { capacities[it] = capacities.getValue(it) + 1 }

		val assignments = linkedMapOf<String, FallenTeam>()
		val overflow = mutableListOf<FallenRegistration>()
		for (team in FallenTeam.entries) {
			val preferred = registrations
				.filter { it.expectedTeam == team }
				.sortedWith(compareBy<FallenRegistration> { it.selectedAt }.thenBy { it.username })
			val retained = preferred.take(capacities.getValue(team))
			retained.forEach { assignments[it.username] = team }
			capacities[team] = capacities.getValue(team) - retained.size
			overflow += preferred.drop(retained.size)
		}
		overflow.sortedWith(compareBy<FallenRegistration> { it.selectedAt }.thenBy { it.username }).forEach { registration ->
			val team = FallenTeam.entries
				.filter { capacities.getValue(it) > 0 }
				.maxWithOrNull(compareBy<FallenTeam> { capacities.getValue(it) }.thenByDescending { it.name })
				?: error("No Fallen team capacity remains")
			assignments[registration.username] = team
			capacities[team] = capacities.getValue(team) - 1
		}
		return assignments
	}

	fun leastPopulatedTeam(assignments: Collection<FallenTeam>): FallenTeam {
		val counts = FallenTeam.entries.associateWith { team -> assignments.count { it == team } }
		return FallenTeam.entries.minWith(compareBy<FallenTeam> { counts.getValue(it) }.thenBy { it.name })
	}
}

@Service
class FallenTeamService(
	private val login: Login,
	private val database: ReactiveDatabase,
) {
	private val scope = CoroutineScope(SupervisorJob())
	private val schemaMutex = Mutex()
	private val rosterMutex = Mutex()
	@Volatile
	private var schemaReady = false
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
		ensureSchema()
		finalizeAssignmentsIfDue()
		return read(username)
	}

	suspend fun selectionForJoiningPlayer(username: String): FallenTeamSelection? {
		ensureSchema()
		finalizeAssignmentsIfDue()
		return read(username) ?: if (Instant.now().isBefore(assignmentInstant)) null else assignLatecomer(username)
	}

	suspend fun finalizedRoster(): Map<FallenTeam, List<String>> = readFinalizedRoster()

	@Scheduled(cron = FallenSchedule.ASSIGNMENT_CRON, zone = FallenSchedule.ASSIGNMENT_ZONE_ID)
	fun finalizeScheduledAssignments() {
		scope.launch {
			ensureSchema()
			finalizeAssignmentsIfDue()
		}
	}

	private suspend fun selectOnce(username: String, team: FallenTeam): FallenSelectionResult {
		ensureSchema()
		val selectedAt = System.currentTimeMillis()
		val inserted = database.execute(
			"INSERT IGNORE INTO fallen_team_selections(username, team, selected_at) VALUES (?, ?, ?)",
			listOf(username, team.name, selectedAt),
		) == 1L

		return if (inserted) {
			FallenSelectionResult.Selected(FallenTeamSelection(username, team, selectedAt))
		} else {
			FallenSelectionResult.AlreadySelected(
				requireNotNull(read(username)) { "Fallen team selection disappeared after a duplicate insert" },
			)
		}
	}

	private suspend fun read(username: String): FallenTeamSelection? = readSelection(
		"SELECT username, team, selected_at, actual_team, assigned_at FROM fallen_team_selections WHERE username = ?",
		listOf(username),
	)

	private suspend fun assignLatecomer(username: String): FallenTeamSelection = database.inTransaction {
		lockAssignments()
		val existing = readForUpdate(username)
		if (existing != null) return@inTransaction existing

		val assignedTeams = database.all(
			"SELECT actual_team FROM fallen_team_selections ORDER BY username FOR UPDATE",
		) { row -> FallenTeam.parse(row.get("actual_team", String::class.java)) }.mapNotNull { it }
		val team = FallenTeamAllocator.leastPopulatedTeam(assignedTeams)
		val assignedAt = System.currentTimeMillis()
		database.execute(
			"""
			INSERT INTO fallen_team_selections(username, team, selected_at, actual_team, assigned_at)
			VALUES (?, ?, ?, ?, ?)
			""".trimIndent(),
			listOf(username, team.name, assignedAt, team.name, assignedAt),
		)
		FallenTeamSelection(username, team, assignedAt, team, assignedAt)
	}

	private suspend fun readForUpdate(username: String): FallenTeamSelection? = readSelection(
		"SELECT username, team, selected_at, actual_team, assigned_at FROM fallen_team_selections WHERE username = ? FOR UPDATE",
		listOf(username),
	)

	private suspend fun readSelection(sql: String, bindings: List<Any?>): FallenTeamSelection? {
		return database.one(sql, bindings) { row ->
			val expectedTeam = FallenTeam.parse(row.get("team", String::class.java)) ?: return@one null
			FallenTeamSelection(
				username = row.get("username", String::class.java)!!,
				expectedTeam = expectedTeam,
				selectedAt = row.get("selected_at", java.lang.Long::class.java)!!.toLong(),
				assignedTeam = FallenTeam.parse(row.get("actual_team", String::class.java)),
				assignedAt = row.get("assigned_at", java.lang.Long::class.java)?.toLong(),
			)
		}
	}

	private suspend fun readFinalizedRoster(): Map<FallenTeam, List<String>> {
		val now = System.currentTimeMillis()
		rosterCache?.takeIf { now - it.loadedAt < ROSTER_CACHE_MILLIS }?.let { return it.roster }
		return rosterMutex.withLock {
			val refreshedNow = System.currentTimeMillis()
			rosterCache?.takeIf { refreshedNow - it.loadedAt < ROSTER_CACHE_MILLIS }?.let { return@withLock it.roster }
			ensureSchema()
			finalizeAssignmentsIfDue()
			val players = FallenTeam.entries.associateWith { mutableListOf<String>() }
			database.all(
				"SELECT username, actual_team FROM fallen_team_selections WHERE actual_team IS NOT NULL ORDER BY username",
			) { row ->
				FallenTeam.parse(row.get("actual_team", String::class.java))?.let { team ->
					team to row.get("username", String::class.java)!!
				}
			}.forEach { pair ->
				if (pair != null) {
					players.getValue(pair.first).add(pair.second)
				}
			}
			val roster = players.mapValues { (_, names) -> names.toList() }
			rosterCache = FallenRosterCache(roster, refreshedNow)
			roster
		}
	}

	private suspend fun ensureSchema() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute(
				"""
				CREATE TABLE IF NOT EXISTS fallen_team_selections (
					username VARCHAR(64) NOT NULL PRIMARY KEY,
					team CHAR(1) NOT NULL,
					selected_at BIGINT NOT NULL,
					actual_team CHAR(1) NULL,
					assigned_at BIGINT NULL,
					CONSTRAINT chk_fallen_team CHECK (team IN ('A', 'B', 'C'))
				)
				""".trimIndent(),
			)
			database.execute(
				"""
				CREATE TABLE IF NOT EXISTS fallen_team_assignment_lock (
					id TINYINT NOT NULL PRIMARY KEY
				)
				""".trimIndent(),
			)
			database.execute("INSERT IGNORE INTO fallen_team_assignment_lock(id) VALUES (1)")
			database.execute("ALTER TABLE fallen_team_selections ADD COLUMN IF NOT EXISTS actual_team CHAR(1) NULL")
			database.execute("ALTER TABLE fallen_team_selections ADD COLUMN IF NOT EXISTS assigned_at BIGINT NULL")
			schemaReady = true
		}
	}

	private suspend fun lockAssignments() {
		check(
			database.one(
				"SELECT id FROM fallen_team_assignment_lock WHERE id = 1 FOR UPDATE",
			) { row -> row.get("id", java.lang.Integer::class.java)!!.toInt() } == 1,
		) { "Fallen team assignment lock row is missing" }
	}

	private suspend fun finalizeAssignmentsIfDue() {
		if (Instant.now().isBefore(assignmentInstant)) return
		database.inTransaction {
			lockAssignments()
			val registrations = database.all(
				"SELECT username, team, selected_at, actual_team FROM fallen_team_selections ORDER BY selected_at, username FOR UPDATE",
			) { row ->
				val expectedTeam = FallenTeam.parse(row.get("team", String::class.java)) ?: return@all null
				FallenRegistration(
					username = row.get("username", String::class.java)!!,
					expectedTeam = expectedTeam,
					selectedAt = row.get("selected_at", java.lang.Long::class.java)!!.toLong(),
					actualTeam = FallenTeam.parse(row.get("actual_team", String::class.java)),
				)
			}.mapNotNull { it }
			if (registrations.isEmpty() || registrations.all { it.actualTeam != null }) {
				return@inTransaction
			}
			check(registrations.none { it.actualTeam != null }) { "Fallen assignment is only partially finalized" }

			val assignments = FallenTeamAllocator.allocate(registrations)
			val assignedAt = System.currentTimeMillis()
			assignments.forEach { (username, team) ->
				database.execute(
					"UPDATE fallen_team_selections SET actual_team = ?, assigned_at = ? WHERE username = ? AND actual_team IS NULL",
					listOf(team.name, assignedAt, username),
				)
			}
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
