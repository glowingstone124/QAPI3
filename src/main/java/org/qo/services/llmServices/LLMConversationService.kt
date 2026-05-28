package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class LLMConversationService {
	private val maxTurns = readInt("LLM_HISTORY_MAX_TURNS", 8).coerceIn(0, 30)
	private val ttlMs = readLong("LLM_HISTORY_TTL_MS", 30 * 60 * 1000L).coerceAtLeast(60_000L)
	private val conversations = ConcurrentHashMap<String, Conversation>()

	fun historyMessages(conversationKey: String): JsonArray {
		val history = conversations[conversationKey] ?: return JsonArray()
		if (System.currentTimeMillis() - history.updatedAt > ttlMs) {
			conversations.remove(conversationKey)
			return JsonArray()
		}
		return JsonArray().apply {
			history.messages.forEach { message ->
				add(JsonObject().apply {
					addProperty("role", message.role)
					addProperty("content", message.content)
				})
			}
		}
	}

	fun append(conversationKey: String, userMessage: String, assistantMessage: String) {
		if (maxTurns <= 0 || userMessage.isBlank() || assistantMessage.isBlank()) {
			return
		}
		val conversation = conversations.compute(conversationKey) { _, existing ->
			val current = existing ?: Conversation(ArrayDeque(), 0L)
			current.messages.addLast(HistoryMessage("user", userMessage))
			current.messages.addLast(HistoryMessage("assistant", assistantMessage))
			while (current.messages.size > maxTurns * 2) {
				current.messages.removeFirst()
			}
			current.updatedAt = System.currentTimeMillis()
			current
		}
		conversation?.updatedAt = System.currentTimeMillis()
	}

	private fun readInt(name: String, defaultValue: Int): Int =
		System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

	private fun readLong(name: String, defaultValue: Long): Long =
		System.getenv(name)?.trim()?.toLongOrNull() ?: defaultValue

	private data class Conversation(
		val messages: ArrayDeque<HistoryMessage>,
		@Volatile var updatedAt: Long,
	)

	private data class HistoryMessage(
		val role: String,
		val content: String,
	)
}
