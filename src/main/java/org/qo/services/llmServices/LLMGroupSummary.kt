package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.withTimeoutOrNull

internal fun withRecentGroupMessages(
	groupContext: JsonObject?,
	messages: List<LLMChatHistoryRecord>,
	currentMessageId: Long?,
	currentUid: Long?,
): JsonObject? {
	if (groupContext == null || messages.isEmpty()) return groupContext
	val currentSourceId = currentMessageId?.let { "onebot:$it" }
	val recent = messages
		.filterNot { it.sourceId == currentSourceId }
		.take(20)
		.asReversed()
	if (recent.isEmpty()) return groupContext
	return groupContext.deepCopy().apply {
		add("recent_participants", JsonArray().apply {
			recent.asReversed().distinctBy { it.uid }.forEach { record ->
				add(JsonObject().apply {
					addProperty("qquid", record.uid)
					addProperty("latest_nickname", record.name)
					addProperty("is_current_sender", record.uid == currentUid)
				})
			}
		})
		add("recent_messages", JsonArray().apply {
			recent.forEach { record ->
				add(JsonObject().apply {
					addProperty("source_id", record.sourceId)
					addProperty("qquid", record.uid)
					addProperty("nickname", record.name)
					addProperty("is_current_sender", record.uid == currentUid)
					addProperty("time", record.time)
					addProperty("text", record.content.take(500))
				})
			}
		})
	}
}

internal suspend fun LLMServices.summarizeGroupContext(existingSummary: String?, messages: List<GroupChatEntry>): String? {
	val provider = providers.current()
	if (provider.apiToken.isBlank() || messages.isEmpty()) return null
	val summaryInstruction = "将群聊历史压缩为可供后续对话使用的事实摘要。保留人物、决定、偏好、未解决问题、路线起终点和重要时间；删除寒暄、重复内容和工具语法。不得添加原文没有的信息。直接输出摘要正文。\n\n${LLMGroupChatPolicy.groupSummaryRules}"
	val maxSummaryOutputTokens = minOf(1200, (provider.summaryContextWindow / 4).coerceAtLeast(1))
	val summaryInput = fitSummaryInput(
		existingSummary = existingSummary,
		messages = messages,
		instruction = summaryInstruction,
		contextWindow = provider.summaryContextWindow,
		outputTokens = maxSummaryOutputTokens,
	)
	val request = JsonObject().apply {
		addProperty("model", provider.summaryModel)
		addProperty("stream", false)
		addProperty("max_tokens", maxSummaryOutputTokens)
		add("thinking", JsonObject().apply { addProperty("type", "disabled") })
		add("messages", JsonArray().apply {
			add(JsonObject().apply {
				addProperty("role", "system")
				addProperty("content", summaryInstruction)
			})
			add(JsonObject().apply {
				addProperty("role", "user")
				addProperty("content", summaryInput)
			})
		})
	}
	return withTimeoutOrNull(groupSummaryTimeoutMs) {
		runCatching {
			val (status, body) = postSummaryUpstream("group-summary", request.toString(), provider.summary)
			if (status !in 200..299) return@runCatching null
			parseUsage(body)?.let { logPromptCacheUsage("group-summary", it) }
			extractAssistantContent(body)
		}.getOrNull()
	}
}

internal suspend fun LLMServices.summarizeGroupAndMemberProfiles(
	groupId: Long,
	existingGroupSummary: String?,
	existingMemberSummaries: Map<Long, String>,
	messages: List<GroupChatEntry>,
): LLMGroupAndMemberSummary? {
	val provider = providers.current()
	if (provider.apiToken.isBlank() || messages.isEmpty()) return null
	val allowedQqUids = messages.mapNotNull { it.uid.toLongOrNull()?.takeIf { uid -> uid > 0 } }.toSet()
	val summaryInstruction = """
		增量更新一个 QQ 群的群聊摘要和人物画像。qquid 是跨群聊、Kotshi Web 与 Minecraft 的唯一身份，绝不能按昵称合并人物。
		只输出一个 JSON 对象，不要 Markdown 或解释：
		{"group_summary":"...","member_profiles":[{"qquid":123,"summary":"..."}]}
		group_summary 必须是合并已有摘要后的完整滚动摘要；member_profiles 只列本批消息中出现的正数 qquid，并输出合并旧画像后的完整画像。没有可靠人物事实时可省略该成员。

		${LLMGroupChatPolicy.groupSummaryRules}

		${LLMGroupChatPolicy.memberSummaryRules}
	""".trimIndent()
	val maxSummaryOutputTokens = minOf(2400, (provider.summaryContextWindow / 3).coerceAtLeast(1))
	val rawInput = buildString {
		append("group_id=").append(groupId).append('\n')
		append("existing_group_summary:\n")
		append(existingGroupSummary.orEmpty()).append("\n\n")
		append("existing_member_profiles:\n")
		existingMemberSummaries.toSortedMap().forEach { (qquid, summary) ->
			append("qquid=").append(qquid).append(": ").append(summary).append('\n')
		}
		append("\nnew_group_messages:\n")
		messages.forEach { entry ->
			append("[").append(entry.time).append("] qquid=").append(entry.uid)
				.append(" name=").append(entry.name).append(": ").append(entry.content).append('\n')
		}
	}
	val inputBudget = (
		provider.summaryContextWindow - maxSummaryOutputTokens - estimateTextTokens(summaryInstruction) - 32
	).coerceAtLeast(1)
	val summaryInput = clipTextToTokens(rawInput, inputBudget)
	val request = JsonObject().apply {
		addProperty("model", provider.summaryModel)
		addProperty("stream", false)
		addProperty("max_tokens", maxSummaryOutputTokens)
		add("thinking", JsonObject().apply { addProperty("type", "disabled") })
		add("messages", JsonArray().apply {
			add(JsonObject().apply {
				addProperty("role", "system")
				addProperty("content", summaryInstruction)
			})
			add(JsonObject().apply {
				addProperty("role", "user")
				addProperty("content", summaryInput)
			})
		})
	}
	return withTimeoutOrNull(groupSummaryTimeoutMs) {
		runCatching {
			val (status, body) = postSummaryUpstream("periodic-group-profile-summary", request.toString(), provider.summary)
			if (status !in 200..299) return@runCatching null
			parseUsage(body)?.let { logPromptCacheUsage("periodic-group-profile-summary", it) }
			val content = extractAssistantContent(body) ?: return@runCatching null
			parseGroupAndMemberSummary(content, allowedQqUids)
		}.getOrNull()
	}
}

internal fun parseGroupAndMemberSummary(content: String, allowedQqUids: Set<Long>): LLMGroupAndMemberSummary? {
	val normalized = content.trim()
		.removePrefix("```json")
		.removePrefix("```")
		.removeSuffix("```")
		.trim()
	val root = runCatching { JsonParser.parseString(normalized).asJsonObject }.getOrNull() ?: return null
	val groupSummary = root.get("group_summary")
		?.takeIf { it.isJsonPrimitive }
		?.asString
		?.trim()
		?.takeIf { it.isNotBlank() }
		?: return null
	val profileItems = root.get("member_profiles")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
	val profiles = profileItems.mapNotNull { item ->
		val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
		val qquid = runCatching { obj.get("qquid")?.asLong }.getOrNull()
			?.takeIf { it in allowedQqUids } ?: return@mapNotNull null
		val summary = obj.get("summary")
			?.takeIf { it.isJsonPrimitive }
			?.asString
			?.trim()
			?.take(2000)
			?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
		LLMObservedMemberSummary(qquid, summary)
	}.distinctBy { it.qqUid }
	return LLMGroupAndMemberSummary(groupSummary, profiles)
}

internal data class LLMGroupAndMemberSummary(
	val groupSummary: String,
	val memberProfiles: List<LLMObservedMemberSummary>,
)

internal data class LLMObservedMemberSummary(
	val qqUid: Long,
	val summary: String,
)
