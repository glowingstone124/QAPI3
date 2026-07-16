package org.qo.services.fallenServices

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import org.qo.services.loginService.Login
import org.springframework.stereotype.Service

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
	val team: FallenTeam,
	val selectedAt: Long
)

sealed interface FallenSelectionResult {
	data class Selected(val selection: FallenTeamSelection) : FallenSelectionResult
	data class AlreadySelected(val selection: FallenTeamSelection) : FallenSelectionResult
	data object InvalidTeam : FallenSelectionResult
}

@Service
class FallenTeamService(private val login: Login) {
	@Volatile
	private var schemaReady = false

	suspend fun selectionForToken(token: String): Pair<String?, FallenTeamSelection?> {
		val (username, errorCode) = login.validate(token)
		if (username == null || errorCode != 0) return null to null
		return username to selectionForUsername(username)
	}

	suspend fun select(token: String, body: String): Pair<String?, FallenSelectionResult?> {
		val (username, errorCode) = login.validate(token)
		if (username == null || errorCode != 0) return null to null
		val team = runCatching {
			FallenTeam.parse(JsonParser.parseString(body).asJsonObject.get("team")?.asString)
		}.getOrNull() ?: return username to FallenSelectionResult.InvalidTeam
		return username to selectOnce(username, team)
	}

	suspend fun selectionForUsername(username: String): FallenTeamSelection? = withContext(Dispatchers.IO) {
		ensureSchema()
		read(username)
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
				"SELECT username, team, selected_at FROM fallen_team_selections WHERE username = ?"
			).use { statement ->
				statement.setString(1, username)
				statement.executeQuery().use { result ->
					if (!result.next()) return null
					val team = FallenTeam.parse(result.getString("team")) ?: return null
					return FallenTeamSelection(
						username = result.getString("username"),
						team = team,
						selectedAt = result.getLong("selected_at")
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
						PRIMARY KEY (username),
						CONSTRAINT chk_fallen_team CHECK (team IN ('A', 'B', 'C'))
					) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
					""".trimIndent()
				)
			}
		}
		schemaReady = true
	}
}
