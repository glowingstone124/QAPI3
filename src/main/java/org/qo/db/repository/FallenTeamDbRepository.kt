package org.qo.db.repository

import io.r2dbc.spi.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.fallenServices.FallenRegistration
import org.qo.services.fallenServices.FallenTeam
import org.qo.services.fallenServices.FallenTeamAllocator
import org.qo.services.fallenServices.FallenTeamSelection
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class FallenTeamDbRepository(
	private val database: ReactiveDatabase,
) {
	private val schemaMutex = Mutex()
	@Volatile
	private var schemaReady = false

	suspend fun ensureSchema() {
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
			addColumnIfMissing("actual_team", "CHAR(1) NULL")
			addColumnIfMissing("assigned_at", "BIGINT NULL")
			schemaReady = true
		}
	}

	private suspend fun addColumnIfMissing(column: String, definition: String) {
		val exists = database.one(
			"""
			SELECT 1
			FROM information_schema.columns
			WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
			  AND LOWER(table_name) = 'fallen_team_selections'
			  AND LOWER(column_name) = LOWER(?)
			LIMIT 1
			""".trimIndent(),
			listOf(column),
		) { true } != null
		if (!exists) {
			database.execute("ALTER TABLE fallen_team_selections ADD COLUMN $column $definition")
		}
	}

	suspend fun insertSelectionIfAbsent(username: String, team: FallenTeam, selectedAt: Long): Boolean {
		ensureSchema()
		return database.execute(
			"INSERT IGNORE INTO fallen_team_selections(username, team, selected_at) VALUES (?, ?, ?)",
			listOf(username, team.name, selectedAt),
		) == 1L
	}

	suspend fun read(username: String): FallenTeamSelection? {
		ensureSchema()
		return readSelection(
			"SELECT username, team, selected_at, actual_team, assigned_at FROM fallen_team_selections WHERE username = ?",
			listOf(username),
		)
	}

	suspend fun readForUpdate(username: String): FallenTeamSelection? {
		ensureSchema()
		return readSelection(
			"SELECT username, team, selected_at, actual_team, assigned_at FROM fallen_team_selections WHERE username = ? FOR UPDATE",
			listOf(username),
		)
	}

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

	suspend fun assignLatecomer(username: String): FallenTeamSelection {
		ensureSchema()
		return database.inTransaction {
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
	}

	suspend fun readFinalizedRoster(): Map<FallenTeam, List<String>> {
		ensureSchema()
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
		return players.mapValues { (_, names) -> names.toList() }
	}

	suspend fun lockAssignments() {
		check(
			database.one(
				"SELECT id FROM fallen_team_assignment_lock WHERE id = 1 FOR UPDATE",
			) { row -> row.get("id", java.lang.Integer::class.java)!!.toInt() } == 1,
		) { "Fallen team assignment lock row is missing" }
	}

	suspend fun finalizeAssignmentsIfDue(assignmentInstant: Instant): Boolean {
		if (Instant.now().isBefore(assignmentInstant)) return false
		ensureSchema()
		return database.inTransaction {
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
				return@inTransaction false
			}
			check(registrations.none { it.actualTeam != null }) { "Fallen assignment is only partially finalized" }

			val assignments = FallenTeamAllocator.allocate(registrations)
			val assignedAt = System.currentTimeMillis()
			for ((username, team) in assignments) {
				database.execute(
					"UPDATE fallen_team_selections SET actual_team = ?, assigned_at = ? WHERE username = ? AND actual_team IS NULL",
					listOf(team.name, assignedAt, username),
				)
			}
			true
		}
	}
}
