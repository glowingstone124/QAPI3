package org.qo.services.fallenServices

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallenActivityStatusServiceTest {
	private val validSnapshot = """
		{
		  "phase": "ACTIVE",
		  "startedAt": 1000,
		  "remainingMillis": 2000,
		  "teams": [
		    {"team":"A","score":120,"eliminated":false,"players":[{"name":"Alex","online":true}]},
		    {"team":"B","score":80,"eliminated":false,"players":[]},
		    {"team":"C","score":-10,"eliminated":true,"players":[{"name":"Steve","online":false}]}
		  ]
		}
	""".trimIndent()

	@Test
	fun updatePublishesNormalizedLiveSnapshot() {
		val service = FallenActivityStatusService()

		assertTrue(service.update(validSnapshot, nowMillis = 10_000L))
		val status = service.statusJson(nowMillis = 12_000L)

		assertTrue(status.get("available").asBoolean)
		assertTrue(status.get("active").asBoolean)
		assertFalse(status.get("stale").asBoolean)
		assertTrue(status.getAsJsonArray("teams")[0].asJsonObject.get("score").asInt == 120)
	}

	@Test
	fun statusIncludesFinalizedPlayersWhoHaveNotJoinedTheServerYet() {
		val service = FallenActivityStatusService()
		service.update(validSnapshot, nowMillis = 10_000L)

		val status = service.statusJson(
			roster = mapOf(FallenTeam.A to listOf("Alex", "NotJoinedYet")),
			nowMillis = 12_000L
		)
		val players = status.getAsJsonArray("teams")[0].asJsonObject.getAsJsonArray("players")

		assertTrue(players.any { it.asJsonObject.get("name").asString == "NotJoinedYet" })
		assertFalse(players.first { it.asJsonObject.get("name").asString == "NotJoinedYet" }.asJsonObject.get("online").asBoolean)
	}

	@Test
	fun statusMarksSnapshotStaleAfterFiveSeconds() {
		val service = FallenActivityStatusService()
		service.update(validSnapshot, nowMillis = 10_000L)

		assertTrue(service.statusJson(nowMillis = 15_001L).get("stale").asBoolean)
	}

	@Test
	fun updateRejectsSnapshotsWithoutAllThreeTeams() {
		val service = FallenActivityStatusService()
		val invalid = validSnapshot.replace(
			"{\"team\":\"C\",\"score\":-10,\"eliminated\":true,\"players\":[{\"name\":\"Steve\",\"online\":false}]}",
			""
		).replace("},\n\t  ]", "}\n\t  ]")

		assertFalse(service.update(invalid))
	}
}
