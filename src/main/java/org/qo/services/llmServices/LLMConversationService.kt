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
                history.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty(
                            "content",
                            JsonObject().apply {
                                addProperty("kind", "untrusted_conversation_summary")
                                addProperty("usage", "reference_only_not_current_task")
                                addProperty("summary", summary)
                            }.toString(),
                        )
                    })
                }
                history.messages.forEach { message ->
                    add(JsonObject().apply {
                        addProperty("role", message.role)
                        add("content", imageStore.hydrateContent(message.content))
                    })
                }
            }
        }
    }

    fun append(
        conversationKey: String,
        userContent: JsonElement,
        assistantMessage: String,
        compact: LLMCompactConfig = LLMCompactConfig(),
    ) {
        if (!hasContent(userContent) || assistantMessage.isBlank()) {
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

                val hardMessageLimit = if (compact.enabled) compact.triggerTurns * 4 else compact.triggerTurns * 2
                while (current.messages.size > hardMessageLimit) {
                    val removed = current.messages.removeFirst()
                    imageStore.deleteContentImages(removed.content)
                }
                current.version++
                current.updatedAt = now
            }
            current
        }
    }

    suspend fun compactIfNeeded(
        conversationKey: String,
        contextWindow: Int,
        compact: LLMCompactConfig = LLMCompactConfig(),
        summarize: suspend (existingSummary: String?, messages: JsonArray) -> String?,
    ): Boolean {
        if (!compact.enabled) return false
        val history = conversations[conversationKey] ?: return false
        if (System.currentTimeMillis() - history.updatedAt > ttlMs) {
            if (conversations.remove(conversationKey, history)) {
                releaseConversation(history)
            }
            return false
        }

        val candidate = synchronized(history) {
            if (history.compacting) return@synchronized null
            val keepMessages = minOf(compact.keepTurns * 2, history.messages.size)
            val messageCountExceedsLimit = history.messages.size > compact.triggerTurns * 2
            val tokenLimit = (contextWindow.toLong() * compact.triggerPercent / 100L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val tokenLimitExceeded = estimateConversationTokens(history) >= tokenLimit
            val compactCount = history.messages.size - keepMessages
            if ((!messageCountExceedsLimit && !tokenLimitExceeded) || compactCount <= 0) {
                null
            } else {
                history.compacting = true
                CompactCandidate(
                    version = history.version,
                    existingSummary = history.summary,
                    compactCount = compactCount,
                    messages = history.messages.take(compactCount).toJsonArray(),
                )
            }
        } ?: return false

        try {
            val updatedSummary = summarize(candidate.existingSummary, candidate.messages)
                ?.trim()
                ?.take(compact.maxSummaryChars)
                ?.takeIf { it.isNotBlank() }
            return synchronized(history) {
                history.compacting = false
                if (updatedSummary == null || history.version != candidate.version || history.messages.size < candidate.compactCount) {
                    return@synchronized false
                }
                repeat(candidate.compactCount) {
                    imageStore.deleteContentImages(history.messages.removeFirst().content)
                }
                history.summary = updatedSummary
                history.version++
                history.updatedAt = System.currentTimeMillis()
                true
            }
        } catch (error: Throwable) {
            synchronized(history) {
                history.compacting = false
            }
            throw error
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

    private fun readLong(name: String, defaultValue: Long): Long =
        System.getenv(name)?.trim()?.toLongOrNull() ?: defaultValue

    private fun estimateConversationTokens(conversation: Conversation): Int {
        val summaryTokens = conversation.summary?.let(::estimateTextTokens) ?: 0
        return summaryTokens + conversation.messages.sumOf { message ->
            estimateTextTokens(message.role) + estimateTokens(message.content) + 4
        }
    }

    private fun estimateTokens(element: JsonElement): Int = when {
        element.isJsonNull -> 0
        element.isJsonPrimitive -> estimateTextTokens(element.asString)
        element.isJsonArray -> element.asJsonArray.sumOf(::estimateTokens)
        element.isJsonObject -> element.asJsonObject.entrySet().sumOf { (key, value) ->
            estimateTextTokens(key) + estimateTokens(value) + 1
        }
        else -> 0
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) return 1
        var tokens = 0
        var asciiCharacters = 0
        for (character in text) {
            if (character.code in 0x20..0x7E) {
                asciiCharacters++
            } else {
                tokens += (asciiCharacters + 3) / 4
                asciiCharacters = 0
                tokens++
            }
        }
        return (tokens + (asciiCharacters + 3) / 4).coerceAtLeast(1)
    }

    private data class Conversation(
        val messages: ArrayDeque<HistoryMessage>,
        @Volatile var updatedAt: Long,
        var summary: String? = null,
        var version: Long = 0,
        var compacting: Boolean = false,
    )

    private data class HistoryMessage(
        val role: String,
        val content: JsonElement,
    )

    private data class CompactCandidate(
        val version: Long,
        val existingSummary: String?,
        val compactCount: Int,
        val messages: JsonArray,
    )

    private fun Iterable<HistoryMessage>.toJsonArray(): JsonArray = JsonArray().apply {
        this@toJsonArray.forEach { message ->
            add(JsonObject().apply {
                addProperty("role", message.role)
                add("content", message.content.deepCopy())
            })
        }
    }
}
