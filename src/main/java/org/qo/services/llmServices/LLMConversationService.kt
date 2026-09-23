package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.db.repository.LlmConversationHistoryDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class LLMConversationService @Autowired constructor(
	private val imageStore: LLMImageStore,
	private val repository: LlmConversationHistoryDbRepository?,
) {
	constructor(imageStore: LLMImageStore) : this(imageStore, null)

	private val ttlMs = readLong("LLM_HISTORY_TTL_MS", 30 * 60 * 1000L).coerceAtLeast(60_000L)
	private val conversations = ConcurrentHashMap<String, Conversation>()
	private val locks = Array(64) { Mutex() }
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
		conversations.clear()
	}

	suspend fun delete(conversationKey: String) = lockFor(conversationKey).withLock {
		val archivedContent = repository?.deleteConversation(conversationKey).orEmpty()
		conversations.remove(conversationKey)
		archivedContent.forEach { content ->
			runCatching { imageStore.deleteContentImages(JsonParser.parseString(content)) }
		}
	}

	suspend fun historyMessages(conversationKey: String): JsonArray = lockFor(conversationKey).withLock {
		val history = loadConversation(conversationKey) ?: return@withLock JsonArray()
		return@withLock buildHistoryMessages(history)
	}

	private fun buildHistoryMessages(history: Conversation): JsonArray = JsonArray().apply {
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

	suspend fun append(
		conversationKey: String,
		userContent: JsonElement,
		assistantMessage: String,
		compact: LLMCompactConfig = LLMCompactConfig(),
	) {
		if (!hasContent(userContent) || assistantMessage.isBlank()) return
		lockFor(conversationKey).withLock {
			val existing = loadConversation(conversationKey)
			val now = System.currentTimeMillis()
			val compactUserContent = imageStore.compactContent(userContent)
			val current = Conversation(
				messages = ArrayDeque(existing?.messages.orEmpty()),
				updatedAt = now,
				summary = existing?.summary,
				version = (existing?.version ?: 0) + 1,
			)
			current.messages.add(HistoryMessage("user", compactUserContent))
			current.messages.add(HistoryMessage("assistant", JsonPrimitive(assistantMessage)))
			val hardMessageLimit = if (compact.enabled) compact.triggerTurns * 4 else compact.triggerTurns * 2
			while (current.messages.size > hardMessageLimit) {
				current.messages.removeFirst()
			}
			repository?.appendTurnAndState(
				conversationKey, compactUserContent.toString(), assistantMessage, now, encodeState(current),
			)
			conversations[conversationKey] = current
		}
	}

	suspend fun compactIfNeeded(
		conversationKey: String,
		contextWindow: Int,
		compact: LLMCompactConfig = LLMCompactConfig(),
		summarize: suspend (existingSummary: String?, messages: JsonArray) -> String?,
	): Boolean {
		if (!compact.enabled) return false
		val candidate = lockFor(conversationKey).withLock {
			val history = loadConversation(conversationKey) ?: return@withLock null
			if (history.compacting) return@withLock null
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
			return lockFor(conversationKey).withLock {
				val history = conversations[conversationKey] ?: return@withLock false
				history.compacting = false
				if (updatedSummary == null || history.version != candidate.version || history.messages.size < candidate.compactCount) {
					return@withLock false
				}
				val updated = Conversation(
					messages = ArrayDeque(history.messages.drop(candidate.compactCount)),
					updatedAt = System.currentTimeMillis(),
					summary = updatedSummary,
					version = history.version + 1,
				)
				repository?.saveState(conversationKey, encodeState(updated), updated.updatedAt)
				conversations[conversationKey] = updated
				true
			}
		} catch (error: Throwable) {
			lockFor(conversationKey).withLock {
				conversations[conversationKey]?.compacting = false
			}
			throw error
		}
	}

	private fun cleanupExpired() {
		val now = System.currentTimeMillis()
		conversations.forEach { (key, conversation) ->
			if (now - conversation.updatedAt > ttlMs) conversations.remove(key, conversation)
		}
	}

	private fun lockFor(key: String): Mutex = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

	private suspend fun loadConversation(key: String): Conversation? {
		val now = System.currentTimeMillis()
		conversations[key]?.let { cached ->
			if (now - cached.updatedAt <= ttlMs) return cached
			conversations.remove(key, cached)
		}
		val stored = repository?.loadState(key) ?: return null
		val restored = decodeState(stored)
		conversations[key] = restored
		return restored
	}

	private fun encodeState(conversation: Conversation): String = JsonObject().apply {
		conversation.summary?.let { addProperty("summary", it) }
		addProperty("updated_at", conversation.updatedAt)
		addProperty("version", conversation.version)
		add("messages", JsonArray().apply {
			conversation.messages.forEach { message ->
				add(JsonObject().apply {
					addProperty("role", message.role)
					add("content", message.content.deepCopy())
				})
			}
		})
	}.toString()

	private fun decodeState(json: String): Conversation {
		val root = JsonParser.parseString(json).asJsonObject
		val messages = ArrayDeque<HistoryMessage>()
		root.getAsJsonArray("messages")?.forEach { item ->
			val message = item.asJsonObject
			messages.add(HistoryMessage(message.get("role").asString, message.get("content").deepCopy()))
		}
		return Conversation(
			messages = messages,
			updatedAt = root.get("updated_at").asLong,
			summary = root.get("summary")?.takeIf { !it.isJsonNull }?.asString,
			version = root.get("version")?.asLong ?: 0,
		)
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
