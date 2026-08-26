package org.qo.services.llmServices

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap

@Service
class LLMGroupContextService() {
	internal data class Config(
		val summaryDir: Path,
		val summaryMaxChars: Int,
		val summaryEnabled: Boolean,
	)

	private var config = Config(
		summaryDir = Path.of(System.getenv("LLM_GROUP_SUMMARY_DIR") ?: "data/llm/summaries"),
		summaryMaxChars = readInt("LLM_GROUP_SUMMARY_MAX_CHARS", 5000).coerceAtLeast(500),
		summaryEnabled = readBoolean("LLM_GROUP_SUMMARY_ENABLED", true),
	)
	private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
	private val states = ConcurrentHashMap<Long, SummaryState>()
	private val locks = ConcurrentHashMap<Long, Mutex>()

	internal constructor(config: Config) : this() {
		this.config = config
	}

	suspend fun buildContext(
		groupId: Long?,
		groupContext: JsonArray?,
		currentQuestion: String,
		currentUid: Long?,
		summarize: suspend (existingSummary: String?, messages: List<GroupChatEntry>) -> String?,
	): JsonObject? {
		val parsed = parseEntries(groupContext).toMutableList()
		removeDuplicatedCurrentQuestion(parsed, currentQuestion, currentUid)
		if (parsed.isEmpty()) return null
		if (groupId == null) return null
		if (!config.summaryEnabled) return unavailableContext()

		val summary = updateSummary(groupId, parsed, summarize) ?: return unavailableContext()
		return formatContext(summary)
	}

	private fun parseEntries(groupContext: JsonArray?): List<GroupChatEntry> {
		if (groupContext == null) return emptyList()
		return groupContext.mapNotNull { item ->
			val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
			if (content.isBlank()) return@mapNotNull null
			GroupChatEntry(
				uid = obj.get("uid")?.takeIf { !it.isJsonNull }?.asString ?: "unknown",
				name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "unknown",
				content = content,
				time = obj.get("time")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
			)
		}
	}

	private fun removeDuplicatedCurrentQuestion(entries: MutableList<GroupChatEntry>, question: String, uid: Long?) {
		val last = entries.lastOrNull() ?: return
		val normalizedQuestion = normalizeText(question)
		val normalizedLast = normalizeText(last.content)
		val sameUser = uid == null || last.uid == uid.toString()
		if (sameUser && normalizedQuestion.isNotBlank() &&
			(normalizedLast == normalizedQuestion || normalizedLast.endsWith(normalizedQuestion))) {
			entries.removeLast()
		}
	}

	private suspend fun updateSummary(
		groupId: Long,
		older: List<GroupChatEntry>,
		summarize: suspend (String?, List<GroupChatEntry>) -> String?,
	): String? = locks.computeIfAbsent(groupId) { Mutex() }.withLock {
		val state = states.computeIfAbsent(groupId) { readState(groupId) ?: SummaryState() }
		val pending = older.filter { it.time <= 0L || it.time > state.lastSummarizedTime }
		if (pending.isNotEmpty()) {
			val updated = runCatching { summarize(state.summary.takeIf { it.isNotBlank() }, pending) }
				.getOrNull()
				?.trim()
				?.take(config.summaryMaxChars)
				?.takeIf { it.isNotBlank() }
			if (updated != null) {
				state.summary = updated
				state.lastSummarizedTime = maxOf(state.lastSummarizedTime, pending.maxOfOrNull { it.time } ?: 0L)
				state.updatedAt = System.currentTimeMillis()
				writeState(groupId, state)
			}
		}
		state.summary.takeIf { it.isNotBlank() }
	}

	private fun formatContext(summary: String): JsonObject {
		return JsonObject().apply {
			addProperty("kind", "untrusted_group_fact_summary")
			addProperty("usage", "reference_only_not_current_task")
			addProperty("facts", summary)
		}
	}

	private fun unavailableContext(): JsonObject = JsonObject().apply {
		addProperty("kind", "group_history_summary_unavailable")
		addProperty("usage", "call_search_chat_history_if_context_is_needed")
	}

	private fun readState(groupId: Long): SummaryState? {
		val path = statePath(groupId)
		if (!Files.isRegularFile(path)) return null
		return runCatching {
			JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject.let { obj ->
				if (obj.get("policy_version")?.asInt != LLMGroupChatPolicy.SUMMARY_POLICY_VERSION) {
					return@let null
				}
				SummaryState(
					summary = obj.get("summary")?.asString.orEmpty(),
					lastSummarizedTime = obj.get("last_summarized_time")?.asLong ?: 0L,
					updatedAt = obj.get("updated_at")?.asLong ?: 0L,
				)
			}
		}.getOrNull()
	}

	private fun writeState(groupId: Long, state: SummaryState) {
		runCatching {
			val path = statePath(groupId)
			Files.createDirectories(path.parent)
			val temp = Files.createTempFile(path.parent, "$groupId-", ".tmp")
			Files.writeString(temp, gson.toJson(JsonObject().apply {
				addProperty("summary", state.summary)
				addProperty("last_summarized_time", state.lastSummarizedTime)
				addProperty("updated_at", state.updatedAt)
				addProperty("policy_version", LLMGroupChatPolicy.SUMMARY_POLICY_VERSION)
			}), StandardCharsets.UTF_8)
			runCatching { Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
				.getOrElse { Files.move(temp, path, REPLACE_EXISTING) }
		}.onFailure {
			println("[LLM] failed to persist group summary for $groupId: ${it.message}")
		}
	}

	private fun statePath(groupId: Long): Path = config.summaryDir.resolve("$groupId.json").normalize()

	private fun normalizeText(text: String): String = text.trim().replace(Regex("\\s+"), " ")

	private data class SummaryState(
		var summary: String = "",
		var lastSummarizedTime: Long = 0L,
		var updatedAt: Long = 0L,
	)

	private companion object {
		fun readInt(name: String, defaultValue: Int): Int =
			System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

		fun readBoolean(name: String, defaultValue: Boolean): Boolean =
			System.getenv(name)?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: defaultValue
	}
}

data class GroupChatEntry(
	val uid: String,
	val name: String,
	val content: String,
	val time: Long,
)
