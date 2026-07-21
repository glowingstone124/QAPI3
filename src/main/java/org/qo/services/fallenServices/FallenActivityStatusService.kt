package org.qo.services.fallenServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.springframework.stereotype.Service

data class FallenActivityPlayer(
	val name: String,
	val online: Boolean
)

data class FallenActivityTeamStatus(
	val team: FallenTeam,
	val score: Int,
	val eliminated: Boolean,
	val players: List<FallenActivityPlayer>
)

data class FallenActivitySnapshot(
	val phase: String,
	val startedAt: Long,
	val remainingMillis: Long,
	val teams: List<FallenActivityTeamStatus>,
	val receivedAt: Long
)

@Service
class FallenActivityStatusService {
	@Volatile
	private var latest: FallenActivitySnapshot? = null

	fun update(body: String): Boolean = update(body, System.currentTimeMillis())

	internal fun update(body: String, nowMillis: Long): Boolean {
		val parsed = parse(body, nowMillis) ?: return false
		latest = parsed
		return true
	}

	fun statusJson(): JsonObject = statusJson(System.currentTimeMillis())
	fun statusJson(roster: Map<FallenTeam, List<String>>): JsonObject = statusJson(roster, System.currentTimeMillis())

	internal fun statusJson(nowMillis: Long): JsonObject = statusJson(emptyMap(), nowMillis)

	internal fun statusJson(roster: Map<FallenTeam, List<String>>, nowMillis: Long): JsonObject {
		val snapshot = latest
		if (snapshot == null) {
			return JsonObject().apply {
				addProperty("available", false)
				addProperty("active", false)
				addProperty("stale", true)
				addProperty("phase", "IDLE")
				addProperty("startedAt", 0L)
				addProperty("remainingMillis", 0L)
				addProperty("updatedAt", 0L)
				add("teams", emptyTeamsJson())
			}
		}

		return JsonObject().apply {
			addProperty("available", true)
			addProperty("active", snapshot.phase in ACTIVE_PHASES)
			addProperty("stale", nowMillis - snapshot.receivedAt > STALE_AFTER_MILLIS)
			addProperty("phase", snapshot.phase)
			addProperty("startedAt", snapshot.startedAt)
			addProperty("remainingMillis", snapshot.remainingMillis)
			addProperty("updatedAt", snapshot.receivedAt)
			add("teams", teamsJson(snapshot.teams, roster))
		}
	}

	private fun parse(body: String, receivedAt: Long): FallenActivitySnapshot? = runCatching {
		val root = JsonParser.parseString(body).asJsonObject
		val phase = root.get("phase")?.asString?.uppercase()
			?.takeIf { it in ALLOWED_PHASES } ?: return null
		val startedAt = root.get("startedAt")?.asLong?.coerceAtLeast(0L) ?: return null
		val remainingMillis = root.get("remainingMillis")?.asLong?.coerceAtLeast(0L) ?: return null
		val inputTeams = root.getAsJsonArray("teams") ?: return null
		if (inputTeams.size() != FallenTeam.entries.size) return null
		val byTeam = inputTeams.map { element ->
			val teamObject = element.asJsonObject
			val team = FallenTeam.parse(teamObject.get("team")?.asString) ?: return null
			val score = teamObject.get("score")?.asInt ?: return null
			val eliminated = teamObject.get("eliminated")?.asBoolean ?: false
			val players = teamObject.getAsJsonArray("players")?.mapNotNull playerLoop@{ playerElement ->
				val player = playerElement.asJsonObject
				val name = player.get("name")?.asString?.trim()
					?.takeIf { it.isNotEmpty() && it.length <= MAX_PLAYER_NAME_LENGTH }
					?: return@playerLoop null
				FallenActivityPlayer(name, player.get("online")?.asBoolean ?: false)
			}.orEmpty().distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
			team to FallenActivityTeamStatus(team, score, eliminated, players)
		}.toMap()
		if (byTeam.size != FallenTeam.entries.size) return null

		FallenActivitySnapshot(
			phase = phase,
			startedAt = startedAt,
			remainingMillis = remainingMillis,
			teams = FallenTeam.entries.map { byTeam.getValue(it) },
			receivedAt = receivedAt
		)
	}.getOrNull()

	private fun teamsJson(
		teams: List<FallenActivityTeamStatus>,
		roster: Map<FallenTeam, List<String>> = emptyMap()
	): JsonArray = JsonArray().apply {
		teams.forEach { teamStatus ->
			val runtimePlayers = teamStatus.players.associateBy { it.name.lowercase() }
			val players = buildList {
				roster[teamStatus.team].orEmpty().forEach { name ->
					add(FallenActivityPlayer(name, runtimePlayers[name.lowercase()]?.online == true))
				}
				val rosterNames = roster[teamStatus.team].orEmpty().mapTo(HashSet()) { it.lowercase() }
				addAll(teamStatus.players.filter { it.name.lowercase() !in rosterNames })
			}.distinctBy { it.name.lowercase() }.sortedWith(
				compareByDescending<FallenActivityPlayer> { it.online }.thenBy { it.name.lowercase() }
			)
			add(JsonObject().apply {
				addProperty("team", teamStatus.team.name)
				addProperty("score", teamStatus.score)
				addProperty("eliminated", teamStatus.eliminated)
				add("players", JsonArray().apply {
					players.forEach { player ->
						add(JsonObject().apply {
							addProperty("name", player.name)
							addProperty("online", player.online)
						})
					}
				})
			})
		}
	}

	private fun emptyTeamsJson(): JsonArray = teamsJson(FallenTeam.entries.map {
		FallenActivityTeamStatus(it, 0, false, emptyList())
	})

	private companion object {
		const val STALE_AFTER_MILLIS = 5_000L
		const val MAX_PLAYER_NAME_LENGTH = 64
		val ALLOWED_PHASES = setOf("IDLE", "DEPLOYMENT", "ACTIVE", "OVERTIME", "ENDED")
		val ACTIVE_PHASES = setOf("DEPLOYMENT", "ACTIVE", "OVERTIME")
	}
}
