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
	val clientTools = extractClientTools(obj, requester?.source)
	obj.remove("conversation_id")
	obj.remove("conversationId")
	val enableMarkdown = extractEnableMarkdownFlag(obj)
	val requesterSource = LLMSource.entries.firstOrNull { it.value == requester?.source }
	val requestedEffort = extractReasoningEffort(obj, LLMReasoningEffort.MEDIUM)
	val reasoningEffort = reasoningEffortForMode(requesterSource, model, requestedEffort)
	val resolvedModel = provider.modelName(model) ?: throw IllegalArgumentException("请求的模型不可用")
	obj.addProperty("model", resolvedModel)
	if (provider.protocol(model) == LLMProtocol.CHAT_COMPLETIONS) {
		obj.addProperty("reasoning_effort", reasoningEffort.wireValue)
		if (provider.name.contains("deepseek", ignoreCase = true)) obj.add("thinking", JsonObject().apply {
			addProperty("type", if (reasoningEffort == LLMReasoningEffort.NONE) "disabled" else "enabled")
		})
	}
	if (provider.protocol(model) == LLMProtocol.ANTHROPIC &&
		listOf("max_tokens", "max_completion_tokens", "max_output_tokens").none(obj::has)) {
		val thinking = reasoningEffort != LLMReasoningEffort.NONE && provider.modelConfig(model).thinkingMode != "disabled"
		obj.addProperty("max_tokens", if (!thinking) 4096 else when (reasoningEffort) {
			LLMReasoningEffort.MAX -> 16384
			LLMReasoningEffort.HIGH -> 8192
			else -> 4096
		})
	}
	// Preserve caller limits; otherwise let the upstream choose its output budget.
	if(stream) obj.add("stream_options",JsonObject().apply { addProperty("include_usage",true) })
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
	val recentGroupMessages = requester
		?.takeIf { it.source == LLMSource.QQ.value && it.groupId != null }
		?.let { current ->
			runCatching { chatHistoryService.search(current.groupId!!, "", limit = 24) }
				.onFailure { println("[LLM] recent group messages lookup failed: ${it.message}") }
				.getOrDefault(emptyList())
		}
	val groupConversationContext = withRecentGroupMessages(
		preparedGroupContext,
		recentGroupMessages.orEmpty(),
		requester?.messageId,
		requester?.uid,
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
		groupConversationContext,
		memberProfileContext,
		resolvedModel,
		enableMarkdown,
	)
	if (clientTools != null) {
		// Keep native assistant/tool-result pairs intact; never truncate a tool chain.
		val compacted = compactClientToolMessages(enrichedTurn.messages, clientTools, provider.contextWindow)
		val inputTokens = estimateTokens(clientContextForEstimate(compacted)) + estimateTokens(clientTools)
		obj.addProperty("max_tokens", clientToolOutputTokens(inputTokens, provider.contextWindow))
		obj.add("messages", compacted)
	} else obj.add("messages", limitMessagesToContextWindow(enrichedTurn.messages, provider.contextWindow, obj))
	return LLMServices.NormalizedRequest(
		preset = model,
		model = obj.get("model").asString,
		body = obj.toString(),
		userContent = if (clientTools != null) clientOriginalUserMessage(requestMessages)?.get("content")?.deepCopy()
			?: enrichedTurn.persistedUserContent else enrichedTurn.persistedUserContent,
		currentUserText = userQuestion,
		enableMarkdown = enableMarkdown,
		reasoningEffort = reasoningEffort,
		pricing = provider.modelConfig(model).pricing?.at(java.time.Instant.now()),
		clientTools = clientTools,
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
	if (requester != null) {
		stableContextParts.add(webSearchRules(includeLinks = isWeb))
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
		"当前用户需要只用一句自然的话回答。"
	requester.source != LLMSource.MINECRAFT.value && qoGroupId != null && requester.groupId != qoGroupId ->
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
		DeepSeek 对话适配：保持自然口语。角色感应来自措辞和反应方式，不要频繁复述东方设定。
		""".trimIndent()
	else -> null
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

internal suspend fun LLMServices.recordConversation(
	requester: LLMServices.LLMRequester,
	userContent: JsonElement,
	responseBody: String,
	provider: LLMProvider,
) {
	val answer = extractAssistantContent(responseBody) ?: return
	recordConversationAnswer(requester, userContent, answer, provider)
}

internal suspend fun LLMServices.recordConversationAnswer(
	requester: LLMServices.LLMRequester,
	userContent: JsonElement,
	answer: String,
	provider: LLMProvider,
) {
	try {
		conversationService.append(requester.conversationKey(), userContent, answer, provider.compact)
	} catch (error: Exception) {
		println("LLM conversation history persistence failed: ${error.message}")
	}
	if (requester.source == "web" && !requester.conversationId.isNullOrBlank()) {
		val userText = extractTextContent(userContent)
		val cleanAnswer = sanitizeAssistantText(answer, enableMarkdown = true)
		if (userText.isNotBlank() && cleanAnswer.isNotBlank()) {
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
