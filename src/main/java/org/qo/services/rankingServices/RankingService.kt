package org.qo.services.rankingServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.springframework.stereotype.Service

@Service
class RankingService(
	private val store: RankingStore
) {
	private val gson = Gson()

	fun download(kind: RankingKind): String {
		return gson.toJson(store.read(kind))
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
}

interface RankingStore {
	fun read(kind: RankingKind): Map<String, Long>
	fun increment(kind: RankingKind, delta: Map<String, Long>): Int
}

enum class RankingKind(val columnName: String) {
	PLACE("place"),
	DESTROY("destroy")
}
