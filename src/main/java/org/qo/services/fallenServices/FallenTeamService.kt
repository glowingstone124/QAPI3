package org.qo.services.fallenServices

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import org.qo.services.loginService.Login
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
	val assignedAt: Long? = null
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
	val actualTeam: FallenTeam? = null
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
}

@Service
class FallenTeamService(private val login: Login) {
	@Volatile
	private var schemaReady = false
	private val assignmentZone = ZoneId.of("Asia/Shanghai")
	private val assignmentInstant = LocalDate.of(2026, 7, 29).atStartOfDay(assignmentZone).toInstant()

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

	suspend fun selectionForUsername(username: String): FallenTeamSelection? = withContext(Dispatchers.IO) {
		ensureSchema()
		finalizeAssignmentsIfDue()
		read(username)
	}

	@Scheduled(cron = "0 0 0 29 7 *", zone = "Asia/Shanghai")
	fun finalizeScheduledAssignments() {
		ensureSchema()
		finalizeAssignmentsIfDue()
	}

	private suspend fun selectOnce(username: String, team: FallenTeam): FallenSelectionResult = withContext(Dispatchers.IO) {
		ensureSchema()
		val selectedAt = System.currentTimeMillis()
		val inserted = ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"INSERT IGNORE INTO fallen_team_selections(username, team, selected_at) VALUES (?, ?, ?)"
			).use { statement ->
				statement.setString(1, username)
				statement.setString(2, team.name)
				statement.setLong(3, selectedAt)
				statement.executeUpdate() == 1
			}
		}

		if (inserted) {
			FallenSelectionResult.Selected(FallenTeamSelection(username, team, selectedAt))
		} else {
			FallenSelectionResult.AlreadySelected(
				requireNotNull(read(username)) { "Fallen team selection disappeared after a duplicate insert" }
			)
		}
	}

	private fun read(username: String): FallenTeamSelection? {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"SELECT username, team, selected_at, actual_team, assigned_at FROM fallen_team_selections WHERE username = ?"
			).use { statement ->
				statement.setString(1, username)
				statement.executeQuery().use { result ->
					if (!result.next()) return null
					val expectedTeam = FallenTeam.parse(result.getString("team")) ?: return null
					return FallenTeamSelection(
						username = result.getString("username"),
						expectedTeam = expectedTeam,
						selectedAt = result.getLong("selected_at"),
						assignedTeam = FallenTeam.parse(result.getString("actual_team")),
						assignedAt = result.getLong("assigned_at").takeUnless { result.wasNull() }
					)
				}
			}
		}
	}

	@Synchronized
	private fun ensureSchema() {
		if (schemaReady) return
		ConnectionPool.getConnection().use { connection ->
			connection.createStatement().use { statement ->
				statement.executeUpdate(
					"""
					CREATE TABLE IF NOT EXISTS fallen_team_selections (
						username VARCHAR(64) NOT NULL,
						team CHAR(1) NOT NULL,
						selected_at BIGINT NOT NULL,
						actual_team CHAR(1) NULL,
						assigned_at BIGINT NULL,
						PRIMARY KEY (username),
						CONSTRAINT chk_fallen_team CHECK (team IN ('A', 'B', 'C'))
					) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
					""".trimIndent()
				)
			}
			ensureColumn(connection, "actual_team", "CHAR(1) NULL")
			ensureColumn(connection, "assigned_at", "BIGINT NULL")
		}
		schemaReady = true
	}

	private fun ensureColumn(connection: java.sql.Connection, name: String, definition: String) {
		val exists = connection.prepareStatement("SHOW COLUMNS FROM fallen_team_selections LIKE ?").use { statement ->
			statement.setString(1, name)
			statement.executeQuery().use { it.next() }
		}
		if (!exists) {
			connection.createStatement().use {
				it.executeUpdate("ALTER TABLE fallen_team_selections ADD COLUMN $name $definition")
			}
		}
	}

	@Synchronized
	private fun finalizeAssignmentsIfDue() {
		if (Instant.now().isBefore(assignmentInstant)) return
		ConnectionPool.getConnection().use { connection ->
			connection.autoCommit = false
			try {
				val registrations = connection.prepareStatement(
					"SELECT username, team, selected_at, actual_team FROM fallen_team_selections ORDER BY selected_at, username FOR UPDATE"
				).use { statement ->
					statement.executeQuery().use { result ->
						buildList {
							while (result.next()) {
								add(FallenRegistration(
									result.getString("username"),
									FallenTeam.parse(result.getString("team")) ?: continue,
									result.getLong("selected_at"),
									FallenTeam.parse(result.getString("actual_team"))
								))
							}
						}
					}
				}
				if (registrations.isEmpty() || registrations.all { it.actualTeam != null }) {
					connection.commit()
					return
				}
				check(registrations.none { it.actualTeam != null }) { "Fallen assignment is only partially finalized" }

				val assignments = FallenTeamAllocator.allocate(registrations)

				val assignedAt = System.currentTimeMillis()
				connection.prepareStatement(
					"UPDATE fallen_team_selections SET actual_team = ?, assigned_at = ? WHERE username = ? AND actual_team IS NULL"
				).use { statement ->
					assignments.forEach { (username, team) ->
						statement.setString(1, team.name)
						statement.setLong(2, assignedAt)
						statement.setString(3, username)
						statement.addBatch()
					}
					statement.executeBatch()
				}
				connection.commit()
			} catch (error: Throwable) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}
	}
}
