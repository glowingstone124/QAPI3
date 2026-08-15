package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class LLMChatHistoryService(private val repository: LLMChatHistoryRepository) {
	suspend fun archiveRequest(groupId: Long, body: String): Int {
		val root = JsonParser.parseString(body).asJsonObject
		val messages = root.getAsJsonArray("messages") ?: return 0
		return archive(groupId, messages)
	}

	suspend fun archiveGroupContext(groupId: Long?, messages: JsonArray?): Int {
		if (groupId == null || messages == null) return 0
		return archive(groupId, messages)
	}

	suspend fun search(
		groupId: Long,
		query: String,
		uid: Long? = null,
		fromTime: Long? = null,
		toTime: Long? = null,
		limit: Int = 12,
	): List<LLMChatHistoryRecord> = repository.search(
		groupId = groupId,
		query = normalize(query).take(200),
		uid = uid,
		fromTime = fromTime,
		toTime = toTime,
		limit = limit.coerceIn(1, 30),
	)

	private suspend fun archive(groupId: Long, messages: JsonArray): Int {
		val now = System.currentTimeMillis()
		val records = messages.asSequence().mapNotNull { item ->
			val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			val content = normalize(string(obj, "content") ?: string(obj, "message")).take(4000)
			if (content.isBlank()) return@mapNotNull null
			val uid = long(obj, "uid") ?: return@mapNotNull null
			val name = normalize(string(obj, "name")).take(160).ifBlank { "qq:$uid" }
			val time = (long(obj, "time") ?: now).let { if (it in 1..9_999_999_999L) it * 1000 else it }
			val suppliedSourceId = string(obj, "source_id") ?: string(obj, "sourceId")
			val sourceId = suppliedSourceId?.trim()?.take(80)?.takeIf { it.isNotBlank() }
				?: stableSourceId(groupId, uid, time, content)
			LLMChatHistoryRecord(sourceId, groupId, uid, name, content, time, now)
		}.distinctBy { it.sourceId }.take(2000).toList()
		return repository.insert(records)
	}

	private fun string(obj: JsonObject, name: String): String? =
		obj.get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

	private fun long(obj: JsonObject, name: String): Long? =
		runCatching { obj.get(name)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull()

	private fun normalize(value: String?): String = value.orEmpty().trim().replace(Regex("\\s+"), " ")

	private fun stableSourceId(groupId: Long, uid: Long, time: Long, content: String): String {
		val digest = MessageDigest.getInstance("SHA-256")
			.digest("$groupId|$uid|$time|$content".toByteArray(StandardCharsets.UTF_8))
		return digest.joinToString("") { "%02x".format(it) }.take(64)
	}
}

data class LLMChatHistoryRecord(
	val sourceId: String,
	val groupId: Long,
	val uid: Long,
	val name: String,
	val content: String,
	val time: Long,
	val createdAt: Long,
)
