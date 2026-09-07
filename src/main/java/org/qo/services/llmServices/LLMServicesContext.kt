package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.qo.datas.Mapping
import org.qo.datas.Nodes
import org.qo.datas.ReactiveDatabase
import org.qo.orm.UserORM
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.qo.services.loginService.Login
import org.qo.services.loginService.QqLoginService
import org.qo.services.messageServices.Message
import org.qo.services.messageServices.Msg
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal suspend fun LLMServices.normalizeRequest(
	body: String,
	stream: Boolean,
	requester: LLMServices.LLMRequester? = null,
	model: String,
	provider: LLMProvider,
): LLMServices.NormalizedRequest {
	val obj = JsonParser.parseString(body).asJsonObject
	obj.remove("conversation_id")
	obj.remove("conversationId")
	val enableMarkdown = extractEnableMarkdownFlag(obj)
	val requesterSource = LLMSource.entries.firstOrNull { it.value == requester?.source }
	val reasoningDefault = defaultReasoningEffort(requesterSource)
	val reasoningEffort = extractReasoningEffort(obj, reasoningDefault)
	if (requester?.source == LLMSource.WEB.value && reasoningEffort == LLMReasoningEffort.NONE) {
		throw IllegalArgumentException("Web reasoning effort must be one of low, high, max")
	}
	val resolvedModel = provider.modelName(model) ?: throw IllegalArgumentException("请求的模型不可用")
	obj.addProperty("model", resolvedModel)
	if (!provider.supportsResponses(model)) {
		obj.addProperty("reasoning_effort", reasoningEffort.wireValue)
		obj.add("thinking", JsonObject().apply {
			addProperty("type", if (reasoningEffort == LLMReasoningEffort.NONE) "disabled" else "enabled")
		})
	}
	requester?.let {
		obj.addProperty("user_id", it.identityKey())
	}
	obj.addProperty("stream", stream)
	if (!obj.has("messages") || !obj.get("messages").isJsonArray) {
		throw IllegalArgumentException("OpenAI chat completions request must contain messages array")
	}

	val requestMessages = obj.getAsJsonArray("messages")
	val userQuestion = latestUserQuestion(requestMessages)

	val groupContext = obj.getAsJsonArray("group_context")
	obj.remove("group_context")
	chatHistoryService.archiveGroupContext(requester?.groupId, groupContext)
	val memberMemories = obj.getAsJsonArray("member_memories")
	obj.remove("member_memories")
	val effectiveGroupContext = groupContext ?: requester
		?.takeIf { it.source == "minecraft" }
		?.let { buildSyncedChatContext() }
	val preparedGroupContext = groupContextService.buildContext(
		groupId = requester?.groupId,
		groupContext = effectiveGroupContext,
		currentQuestion = userQuestion,
		currentUid = requester?.uid,
		summarize = ::summarizeGroupContext,
	)
	val profileUids = participantUids(memberMemories, effectiveGroupContext, requester?.uid)
	val storedProfiles = try {
		requester?.takeIf { it.source in setOf(LLMSource.QQ.value, LLMSource.WEB.value) }?.let {
			memberProfileService.observeRequester(it.uid, it.name, it.groupId)
		}
		memberProfileService.profiles(profileUids, requester?.groupId)
	} catch (error: Exception) {
		println("[LLM] member profile lookup failed: ${error.message}")
		emptyList()
	}
	val memberProfileContext = memberProfileContextService.buildContext(memberMemories, requester?.uid, storedProfiles)
	val enrichedTurn = enrichMessages(
		requestMessages,
		requester,
		preparedGroupContext,
		memberProfileContext,
		resolvedModel,
		enableMarkdown,
	)
	obj.add("messages", limitMessagesToContextWindow(enrichedTurn.messages, provider.contextWindow, obj))
	return LLMServices.NormalizedRequest(
		preset = model,
		model = obj.get("model").asString,
		body = obj.toString(),
		userContent = enrichedTurn.persistedUserContent,
		currentUserText = userQuestion,
		enableMarkdown = enableMarkdown,
		reasoningEffort = reasoningEffort,
	)
}

internal suspend fun LLMServices.enrichMessages(
	messages: JsonArray,
	requester: LLMServices.LLMRequester?,
	groupContext: JsonObject?,
	memberProfileContext: String?,
	model: String,
	enableMarkdown: Boolean,
): LLMPromptCacheLayout.CurrentTurn {
	val enriched = JsonArray()
	val userQuestion = latestUserQuestion(messages)
	val isWeb = requester?.source == "web"
	val stableContextParts = mutableListOf<String>()
	val serverMetadataParts = mutableListOf<String>()
	val referenceContextParts = mutableListOf<String>()
	val basicPrompt = systemPrompt.current().trim()
	if (basicPrompt.isNotBlank()) {
		stableContextParts.add(basicPrompt)
	}
	modelConversationAdapter(model)?.let(stableContextParts::add)
	if (toolService.enabled()) {
		stableContextParts.add(LLMToolInstructions.systemRules)
	}
	if (webSearchEnabled && requester != null) {
		stableContextParts.add(webSearchRules())
	}
	stableContextParts.add(LLMServices.hardOutputRules(enableMarkdown, isWeb))
	val groupConversation = groupContext != null || requester?.groupId != null
	if (groupConversation && !isWeb) stableContextParts.add(LLMGroupChatPolicy.systemRules)
	requester?.takeIf { !isWeb }?.let { currentRequester ->
		requesterSpecificRules(currentRequester)?.let(stableContextParts::add)
		buildMinecraftRelatedContext(currentRequester.minecraftRelated)?.let { minecraftContext ->
			serverMetadataParts.add(minecraftContext)
		}
	}
	requester?.takeIf { !isWeb && qoGroupId != null && it.groupId == qoGroupId }?.let { qoRequester ->
		ragService.buildContext(userQuestion, qoRequester.groupId)?.let(referenceContextParts::add)
	}
	if (!isWeb) {
		memoryService.buildContext(requester?.groupId, userQuestion)?.let {
			referenceContextParts.add(it)
		}
	}
	memberProfileContext?.let(referenceContextParts::add)
	enriched.add(JsonObject().apply {
		addProperty("role", "system")
		addProperty("content", stableContextParts.joinToString("\n\n"))
	})
	requester
		?.takeIf { (groupContext == null || it.source == "qq") && it.source != "web" }
		?.let {
			conversationService.historyMessages(it.conversationKey()).forEach { message ->
				enriched.add(message)
			}
		}
	val currentTurn = LLMPromptCacheLayout.prepareCurrentTurn(
		messages,
		LLMPromptCacheLayout.Context(
			sender = requester?.let {
				LLMPromptCacheLayout.Sender(it.uid, it.name, it.source, it.groupId)
			},
			serverMetadata = serverMetadataParts,
			referenceContext = referenceContextParts,
			groupHistory = groupContext,
			currentMessageOnly = groupConversation,
		),
	)
	currentTurn.messages.forEach(enriched::add)
	return currentTurn.copy(messages = enriched)
}

internal fun LLMServices.requesterSpecificRules(requester: LLMServices.LLMRequester): String? = when {
	requester.uid in ultraBriefQqUids ->
		"当前用户需要最简短回答：除非必须澄清安全或事实风险，否则只用一句自然的话回答。"
	qoGroupId != null && requester.groupId != qoGroupId ->
		"本条消息不来自 QO 唯一官方群。不要提及、检索、推断或泄露 QO 服务器的内部资料、规则、账号、状态、指令或群聊历史；普通知识和日常聊天仍可正常回答。"
	else -> null
}

internal fun LLMServices.modelConversationAdapter(model: String): String? = when {
	model.contains("luna", ignoreCase = true) ->
		"""
		Luna 对话适配：保持真实群聊感。简单问题直接回应，不要自动整理成报告、列表或说明书；先判断对方是在提问、吐槽、开玩笑还是接话，再选择语气。允许短句和自然停顿，不要每次都先确认需求或复述问题。掌握画像时自然调整表达，不要刻意强调记得对方。
		""".trimIndent()
	model.contains("deepseek", ignoreCase = true) ->
		"""
		DeepSeek 对话适配：保持自然口语，但不要过度演绎角色、擅自增加亲密关系或虚构共同经历。角色感应来自措辞和反应方式，不要频繁复述东方设定。
		""".trimIndent()
	else -> null
}

internal fun LLMServices.limitMessagesToContextWindow(messages: JsonArray, contextWindow: Int, request: JsonObject): JsonArray {
	val outputTokens = requestedOutputTokens(request, contextWindow)
	val inputBudget = (contextWindow - outputTokens).coerceAtLeast(1)
	val entries = messages.toList()
	if (estimateTokens(messages) <= inputBudget || entries.isEmpty()) return messages

	val selected = BooleanArray(entries.size)
	var usedTokens = 0
	fun select(index: Int) {
		if (index !in entries.indices || selected[index]) return
		selected[index] = true
		usedTokens += estimateTokens(entries[index])
	}

	select(entries.indexOfFirst { it.isJsonObject && it.asJsonObject.get("role")?.asString == "system" })
	val latestUser = entries.indexOfLast { it.isJsonObject && it.asJsonObject.get("role")?.asString == "user" }
	select(if (latestUser >= 0) latestUser else entries.lastIndex)
	for (index in entries.lastIndex downTo 0) {
		if (selected[index]) continue
		val cost = estimateTokens(entries[index])
		if (usedTokens + cost <= inputBudget) select(index)
	}

	return JsonArray().apply {
		entries.forEachIndexed { index, entry ->
			if (selected[index]) add(entry)
		}
	}
}

internal fun LLMServices.requestedOutputTokens(request: JsonObject, contextWindow: Int): Int {
	val explicit = listOf("max_tokens", "max_output_tokens").firstNotNullOfOrNull { key ->
		request.get(key)?.let { runCatching { it.asInt }.getOrNull() }
	}
	return (explicit ?: minOf(4096, contextWindow / 4)).coerceAtLeast(0)
}

internal fun LLMServices.estimateTokens(element: JsonElement): Int = when {
	element.isJsonNull -> 0
	element.isJsonPrimitive -> estimateTextTokens(element.asString)
	element.isJsonArray -> element.asJsonArray.sumOf(::estimateTokens)
	element.isJsonObject -> element.asJsonObject.entrySet().sumOf { (key, value) ->
		estimateTextTokens(key) + estimateTokens(value) + 1
	}
	else -> 0
}

internal fun LLMServices.estimateTextTokens(text: String): Int {
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
	tokens += (asciiCharacters + 3) / 4
	return tokens.coerceAtLeast(1)
}

internal fun LLMServices.fitSummaryInput(
	existingSummary: String?,
	messages: List<GroupChatEntry>,
	instruction: String,
	contextWindow: Int,
	outputTokens: Int,
): String {
	val inputBudget = (
		contextWindow - outputTokens - estimateTextTokens(instruction) - 16
	).coerceAtLeast(1)
	var selectedMessages = messages
	while (selectedMessages.size > 1 && estimateTextTokens(summaryInputText(existingSummary, selectedMessages)) > inputBudget) {
		selectedMessages = selectedMessages.drop(1)
	}
	return clipTextToTokens(summaryInputText(existingSummary, selectedMessages), inputBudget)
}

internal fun LLMServices.summaryInputText(existingSummary: String?, messages: List<GroupChatEntry>): String = buildString {
	if (!existingSummary.isNullOrBlank()) {
		append("已有摘要：\n").append(existingSummary).append("\n\n")
	}
	append("需要合并的新消息：\n")
	append(messages.joinToString("\n") { entry ->
		val prefix = if (entry.time > 0) "[${entry.time}] " else ""
		"$prefix${entry.name}(${entry.uid}): ${entry.content}"
	})
}

internal fun LLMServices.clipTextToTokens(text: String, maxTokens: Int): String {
	if (estimateTextTokens(text) <= maxTokens) return text
	var low = 0
	var high = text.length
	while (low < high) {
		val middle = (low + high) / 2
		if (estimateTextTokens(text.substring(middle)) <= maxTokens) {
			high = middle
		} else {
			low = middle + 1
		}
	}
	return text.substring(low)
}

internal fun LLMServices.buildMinecraftRelatedContext(minecraftRelated: LLMServices.MinecraftRelated?): String? {
	if (minecraftRelated == null) {
		return null
	}
	return """
          当前 Minecraft 玩家状态：
          - 坐标/维度：${minecraftRelated.coord.ifBlank { "未提供" }}
          - 生命值：${minecraftRelated.hp.ifBlank { "未提供" }}
       """.trimIndent()
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
			val response = postSummaryUpstream("group-summary", request.toString(), provider.summary)
			if (!response.status.isSuccess()) return@runCatching null
			val body = response.bodyAsText()
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
			val response = postSummaryUpstream("periodic-group-profile-summary", request.toString(), provider.summary)
			if (!response.status.isSuccess()) return@runCatching null
			val body = response.bodyAsText()
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

internal fun LLMServices.buildSyncedChatContext(): JsonArray {
	val context = JsonArray()
	Msg.msgQueue.forEach { message ->
		context.add(JsonObject().apply {
			addProperty("uid", syncedMessageUid(message))
			addProperty("name", syncedMessageName(message))
			addProperty("content", message.message)
			addProperty("time", message.time)
		})
	}
	return context
}

internal fun LLMServices.participantUids(vararg sources: Any?): List<Long> {
	val uids = linkedSetOf<Long>()
	sources.forEach { source ->
		when (source) {
			is Long -> if (source > 0) uids.add(source)
			is JsonArray -> source.forEach { item ->
				item.takeIf { it.isJsonObject }
					?.asJsonObject
					?.get("uid")
					?.takeIf { !it.isJsonNull }
					?.let { runCatching { it.asLong }.getOrNull() }
					?.takeIf { it > 0 }
					?.let(uids::add)
			}
		}
	}
	return uids.take(100)
}

internal fun LLMServices.syncedMessageUid(message: Message): String =
	if (message.from == 0) message.sender else "${message.from}:${message.sender}"

internal fun LLMServices.syncedMessageName(message: Message): String {
	val sender = message.sender.ifBlank { "unknown" }
	return when (message.from) {
		0 -> "QQ/$sender"
		1 -> "Minecraft/$sender"
		2 -> "System/$sender"
		3 -> "Web/$sender"
		4 -> "Minecraft-Creative/$sender"
		else -> "Synced/$sender"
	}
}

internal fun LLMServices.minecraftGroupId(serverId: Int): Long? {
	val specific = System.getenv("LLM_MINECRAFT_GROUP_ID_$serverId")
		?.trim()
		?.toLongOrNull()
	if (specific != null) {
		return specific
	}
	return System.getenv("LLM_MINECRAFT_GROUP_ID")
		?.trim()
		?.toLongOrNull()
}

internal fun LLMServices.latestUserQuestion(messages: JsonArray): String {
	return latestUserMessage(messages)
		?.get("content")
		?.let(::extractTextContent)
		.orEmpty()
}

internal fun LLMServices.latestUserMessage(messages: JsonArray): JsonObject? {
	for (index in messages.size() - 1 downTo 0) {
		val message = messages[index].takeIf { it.isJsonObject }?.asJsonObject ?: continue
		if (message.get("role")?.asString == "user") {
			return message
		}
	}
	return null
}

internal fun LLMServices.extractTextContent(content: JsonElement): String {
	if (content.isJsonPrimitive) {
		return content.asString
	}
	if (!content.isJsonArray) {
		return ""
	}
	return content.asJsonArray
		.mapNotNull { part ->
			val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			when (obj.get("type")?.asString) {
				"text", "input_text" -> obj.get("text")
					?.takeIf { it.isJsonPrimitive }
					?.asString

				else -> null
			}
		}
		.filter { it.isNotBlank() }
		.joinToString("\n")
}

internal fun LLMServices.reserveRequest(qqUid: Long): Boolean {
	return redis.setIfAbsentWithExpire("llm:req:$qqUid", "1", DatabaseType.QO_ASSISTANT_DATABASE.value, 2)
		.ignoreException() ?: true
}

internal fun LLMServices.reserveQuota(principal: LLMPrincipal, clientRequestId: String?): LLMQuotaDecision {
	return if (clientRequestId.isNullOrBlank()) {
		dailyQuotaService.reserve(principal)
	} else {
		dailyQuotaService.reserve(principal, clientRequestId)
	}
}

internal fun LLMServices.quotaFailure(decision: LLMQuotaDecision, principal: LLMPrincipal): LLMNonStreamResult? {
	val (status, code) = when (decision.status) {
		LLMQuotaStatus.ACCEPTED -> return null
		LLMQuotaStatus.EXCEEDED -> 429 to "daily_quota_exceeded"
		LLMQuotaStatus.DUPLICATE -> 409 to "duplicate_request"
		LLMQuotaStatus.UNAVAILABLE -> 503 to "quota_unavailable"
	}
	return LLMNonStreamResult(status, errorJson(code, quotaErrorMessage(decision.status, principal)), decision.view)
}

internal fun LLMServices.quotaStreamFailure(decision: LLMQuotaDecision, principal: LLMPrincipal): LLMStreamResult? {
	val failure = quotaFailure(decision, principal) ?: return null
	return LLMStreamResult(failure.status, flowOfText(failure.body), failure.quota)
}

internal fun LLMServices.quotaErrorMessage(status: LLMQuotaStatus, principal: LLMPrincipal): String = when (status) {
	LLMQuotaStatus.ACCEPTED -> ""
	LLMQuotaStatus.EXCEEDED -> if (principal.hasAccount) {
		"今天的对话额度已经用完"
	} else {
		"今天的游客对话额度已用完（每日 20 轮）。加入或注册 QO 账户可获取更多额度（每日 50 轮）！"
	}
	LLMQuotaStatus.DUPLICATE -> "该请求已经提交，请勿重复发送"
	LLMQuotaStatus.UNAVAILABLE -> "额度服务暂时不可用，请稍后重试"
}

internal fun LLMServices.quotaJson(view: LLMQuotaView): String = JsonObject().apply {
	addProperty("limit", view.limit)
	addProperty("used", view.used)
	addProperty("remaining", view.remaining)
	addProperty("reset_at", view.resetAtEpochSeconds)
}.toString()

internal fun LLMServices.recordConversation(
	requester: LLMServices.LLMRequester,
	userContent: JsonElement,
	responseBody: String,
	provider: LLMProvider,
) {
	val answer = extractAssistantContent(responseBody) ?: return
	recordConversationAnswer(requester, userContent, answer, provider)
}

internal fun LLMServices.recordConversationAnswer(
	requester: LLMServices.LLMRequester,
	userContent: JsonElement,
	answer: String,
	provider: LLMProvider,
) {
	try {
		conversationService.append(requester.conversationKey(), userContent, answer, provider.compact)
	} catch (error: Exception) {
		println("LLM conversation history persistence failed: ${error.message}")
		return
	}
	if (requester.source == "web" && !requester.conversationId.isNullOrBlank()) {
		val userText = extractTextContent(userContent)
		val cleanAnswer = sanitizeAssistantText(answer, enableMarkdown = true)
		if (userText.isNotBlank() && cleanAnswer.isNotBlank()) {
			initializationScope.launch {
				try {
					kotshiConversationService.appendTurn(
						uid = requester.uid,
						conversationId = requester.conversationId,
						userContent = userText,
						assistantContent = cleanAnswer,
						model = requester.model,
					)
				} catch (e: Exception) {
					println("[Kotshi] conversation persistence failed: ${e.message}")
				}
			}
		}
	}
	initializationScope.launch {
		try {
			conversationService.compactIfNeeded(requester.conversationKey(), provider.contextWindow, provider.compact) { existingSummary, messages ->
				summarizeConversation(existingSummary, messages, provider)
			}
		} catch (error: Exception) {
			println("LLM conversation compaction failed: ${error.message}")
		}
	}
}
