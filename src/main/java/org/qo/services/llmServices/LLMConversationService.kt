package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class LLMConversationService(
    private val imageStore: LLMImageStore,
) {
    private val maxTurns = readInt("LLM_HISTORY_MAX_TURNS", 12).coerceIn(0, 30)
    private val ttlMs = readLong("LLM_HISTORY_TTL_MS", 30 * 60 * 1000L).coerceAtLeast(60_000L)
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "llm-conversation-cleanup").apply { isDaemon = true }
    }

    init {
        val cleanupPeriodMs = ttlMs.coerceAtMost(5 * 60 * 1000L).coerceAtLeast(60_000L)
        cleanupExecutor.scheduleAtFixedRate(
            { cleanupExpired() },
            cleanupPeriodMs,
            cleanupPeriodMs,
            TimeUnit.MILLISECONDS,
        )
    }

    @PreDestroy
    fun shutdown() {
        cleanupExecutor.shutdownNow()
        conversations.values.forEach(::releaseConversation)
        conversations.clear()
    }

    fun historyMessages(conversationKey: String): JsonArray {
        val history = conversations[conversationKey] ?: return JsonArray()
        if (System.currentTimeMillis() - history.updatedAt > ttlMs) {
            if (conversations.remove(conversationKey, history)) {
                releaseConversation(history)
            }
            return JsonArray()
        }

        return synchronized(history) {
            JsonArray().apply {
                history.messages.forEach { message ->
                    add(JsonObject().apply {
                        addProperty("role", message.role)
                        add("content", imageStore.hydrateContent(message.content))
                    })
                }
            }
        }
    }

    fun append(conversationKey: String, userContent: JsonElement, assistantMessage: String) {
        if (maxTurns <= 0 || !hasContent(userContent) || assistantMessage.isBlank()) {
            return
        }

        val compactUserContent = imageStore.compactContent(userContent)
        conversations.compute(conversationKey) { _, existing ->
            val now = System.currentTimeMillis()
            val current = if (existing == null || now - existing.updatedAt > ttlMs) {
                existing?.let(::releaseConversation)
                Conversation(ArrayDeque(), now)
            } else {
                existing
            }

            synchronized(current) {
                current.messages.add(HistoryMessage("user", compactUserContent))
                current.messages.add(HistoryMessage("assistant", JsonPrimitive(assistantMessage)))

                while (current.messages.size > maxTurns * 2) {
                    val removed = current.messages.removeFirst()
                    imageStore.deleteContentImages(removed.content)
                }
                current.updatedAt = now
            }
            current
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        conversations.forEach { (key, conversation) ->
            if (now - conversation.updatedAt > ttlMs && conversations.remove(key, conversation)) {
                releaseConversation(conversation)
            }
        }
    }

    private fun releaseConversation(conversation: Conversation) {
        synchronized(conversation) {
            conversation.messages.forEach { message ->
                imageStore.deleteContentImages(message.content)
            }
            conversation.messages.clear()
        }
    }

    private fun hasContent(content: JsonElement): Boolean {
        return when {
            content.isJsonNull -> false
            content.isJsonPrimitive -> content.asString.isNotBlank()
            content.isJsonArray -> content.asJsonArray.size() > 0
            else -> true
        }
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
        val content: JsonElement,
    )
}
