package org.qo.services.rankingServices

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RankingServiceTest {
	@Test
	fun upload_mergesRankingDeltas() {
		val service = RankingService(InMemoryRankingStore())

		service.upload(RankingKind.PLACE, """{"Steve":2,"Alex":3}""")
		service.upload(RankingKind.PLACE, """{"Steve":5}""")

		val ranking = JsonParser.parseString(service.download(RankingKind.PLACE)).asJsonObject
		assertEquals(7L, ranking.get("Steve").asLong)
		assertEquals(3L, ranking.get("Alex").asLong)
	}

	private class InMemoryRankingStore : RankingStore {
		private val data = mutableMapOf<RankingKind, MutableMap<String, Long>>()

		override fun read(kind: RankingKind): Map<String, Long> {
			return data[kind]?.toMap() ?: emptyMap()
		}

		override fun increment(kind: RankingKind, delta: Map<String, Long>): Int {
			val ranking = data.computeIfAbsent(kind) { linkedMapOf() }
			delta.forEach { (username, amount) ->
				ranking[username] = (ranking[username] ?: 0L) + amount
			}
			return delta.size
		}
	}
}
