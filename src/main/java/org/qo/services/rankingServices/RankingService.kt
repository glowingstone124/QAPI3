package org.qo.services.rankingServices

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.springframework.stereotype.Service

@Service
class RankingService(
	private val store: RankingStore
) {
	private val gson = Gson()

	fun download(kind: RankingKind, limit: Int = DEFAULT_LIMIT): String {
		return gson.toJson(store.read(kind, normalizeLimit(limit)))
	}

	fun leaderboards(limit: Int = DEFAULT_LIMIT): JsonObject {
		val normalizedLimit = normalizeLimit(limit)
		return JsonObject().apply {
			addProperty("generatedAt", System.currentTimeMillis())
			add("rankings", JsonObject().apply {
				RankingKind.entries.forEach { kind ->
					add(kind.id, JsonArray().apply {
						store.read(kind, normalizedLimit).entries.forEachIndexed { index, entry ->
							add(JsonObject().apply {
								addProperty("rank", index + 1)
								addProperty("name", entry.key)
								addProperty("value", entry.value)
								addProperty("unit", kind.unit)
							})
						}
					})
				}
			})
		}
	}

	fun upload(kind: RankingKind, body: String): JsonObject {
		val delta = parseBody(body)
		val updated = store.increment(kind, delta)

		return JsonObject().apply {
			addProperty("code", 0)
			addProperty("updated", updated)
		}
	}

	private fun parseBody(body: String): Map<String, Long> {
		if (body.isBlank()) return emptyMap()
		val json = JsonParser.parseString(body)
		if (!json.isJsonObject) return emptyMap()
		return json.asJsonObject.entrySet().mapNotNull { (name, value) ->
			if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return@mapNotNull null
			val amount = value.asLong
			if (name.isBlank() || amount <= 0) return@mapNotNull null
			name to amount
		}.toMap()
	}

	private fun normalizeLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

	private companion object {
		const val DEFAULT_LIMIT = 50
		const val MAX_LIMIT = 100
	}
}

interface RankingStore {
	fun read(kind: RankingKind, limit: Int): Map<String, Long>
	fun increment(kind: RankingKind, delta: Map<String, Long>): Int
}

enum class RankingKind(val id: String, val columnName: String, val unit: String) {
	PLACE("place", "place", "blocks"),
	DESTROY("destroy", "destroy", "blocks"),
	PLAYTIME("playtime", "playtime", "minutes")
}
