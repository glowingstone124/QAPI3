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

	@Test
	fun leaderboards_include_block_and_playtime_rankings_with_limits() {
		val store = InMemoryRankingStore()
		val service = RankingService(store)
		store.increment(RankingKind.DESTROY, mapOf("Alex" to 9, "Steve" to 12))
		store.increment(RankingKind.PLAYTIME, mapOf("Alex" to 120, "Steve" to 30))

		val result = service.leaderboards(1)
		val rankings = result.getAsJsonObject("rankings")

		assertEquals("Steve", rankings.getAsJsonArray("destroy")[0].asJsonObject.get("name").asString)
		assertEquals(12L, rankings.getAsJsonArray("destroy")[0].asJsonObject.get("value").asLong)
		assertEquals("Alex", rankings.getAsJsonArray("playtime")[0].asJsonObject.get("name").asString)
		assertEquals(1, rankings.getAsJsonArray("playtime").size())
	}

	private class InMemoryRankingStore : RankingStore {
		private val data = mutableMapOf<RankingKind, MutableMap<String, Long>>()

		override fun read(kind: RankingKind, limit: Int): Map<String, Long> {
			return data[kind].orEmpty().entries
				.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
				.take(limit)
				.associateTo(linkedMapOf()) { it.key to it.value }
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
