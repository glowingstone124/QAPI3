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

internal suspend fun LLMServices.completeWithOptionalTools(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	source: String,
	provider: LLMProvider,
): Pair<Int, String> {
	if (provider.protocol(request.preset) == LLMProtocol.ANTHROPIC) {
		return completeWithAnthropicApi(request, requester, source, provider)
	}
	if (provider.supportsResponses(request.preset)) {
		return completeWithResponsesApi(request, requester, source, provider)
	}
	val functionTools = if (toolService.enabled()) toolService.definitions() else JsonArray()
	val obj = LLMWebSearchAdapter.enableChatCompletions(request.body, functionTools)

	var latestStatus = 502
	var latestBody = ""
	var totalUsage: LLMServices.Usage? = null
	repeat(maxToolRounds) { round ->
		val body = obj.toString()
		val response = postUpstream("$source/tool-round-${round + 1}", body, provider)
		latestStatus = response.status.value
		latestBody = response.bodyAsText()
		if (!response.status.isSuccess()) {
			return latestStatus to withUsage(latestBody,totalUsage)
		}
		totalUsage = accumulateUsage(totalUsage, parseUsage(latestBody))
		val toolCalls = extractToolCalls(latestBody)
		if (toolCalls.isEmpty()) {
			if (containsToolMarkup(latestBody)) {
				return 502 to withUsage(errorJson("invalid_tool_call", "LLM 输出了无法解析的工具调用"), totalUsage)
			}
			return latestStatus to sanitizeResponseBody(withUsage(latestBody, totalUsage), request.enableMarkdown)
		}
		appendAssistantToolCallMessage(obj.getAsJsonArray("messages"), latestBody, toolCalls)
		for (call in toolCalls) {
			obj.getAsJsonArray("messages").add(JsonObject().apply {
				addProperty("role", "tool")
				addProperty("tool_call_id", call.id)
				addProperty("name", call.name)
				addProperty("content", toolService.execute(call.name, call.arguments, requester.toolContext(request.currentUserText)))
			})
		}
	}
	return 502 to withUsage(errorJson("tool_round_limit", "工具调用轮数超过限制，请调高 LLM_TOOL_MAX_ROUNDS"), totalUsage)
}

internal suspend fun LLMServices.completeWithResponsesApi(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	source: String,
	provider: LLMProvider,
): Pair<Int, String> {
	val functionTools = if (toolService.enabled()) toolService.definitions() else JsonArray()
	val body = LLMResponsesAdapter.fromChatRequest(
		request.body,
		functionTools,
		reasoningEffort = request.reasoningEffort,
	)
	var totalUsage: LLMServices.Usage? = null
	repeat(maxToolRounds) { round ->
		val response = postUpstream("$source/responses-round-${round + 1}", body.toString(), provider, provider.responsesUrl)
		val responseText = response.bodyAsText()
		if (debugPrompt) {
			println("[LLM] responses result source=$source round=${round + 1} ${LLMResponsesAdapter.outputSummary(responseText)}")
		}
		if (!response.status.isSuccess()) {
			return response.status.value to withUsage(responseText,totalUsage)
		}
		totalUsage = accumulateUsage(totalUsage, parseUsage(responseText))
		val responseRoot = JsonParser.parseString(responseText).asJsonObject
		if (responseRoot.has("error") || responseRoot.get("status")?.asString in setOf("failed", "incomplete")) {
			return 502 to withUsage(errorJson("upstream_error", "Responses API 未成功完成请求"), totalUsage)
		}
		val functionCalls = LLMResponsesAdapter.functionCalls(responseText)
		if (functionCalls.isEmpty()) {
			return response.status.value to sanitizeResponseBody(
				withUsage(LLMResponsesAdapter.toChatCompletion(responseText),totalUsage),
				request.enableMarkdown,
			)
		}
		val outputs = linkedMapOf<String, String>()
		for (call in functionCalls) {
			outputs[call.callId] = toolService.execute(call.name, call.arguments, requester.toolContext(request.currentUserText))
		}
		LLMResponsesAdapter.appendToolOutputs(body, responseText, outputs)
	}
	return 502 to withUsage(errorJson("tool_round_limit", "工具调用轮数超过限制，请调高 LLM_TOOL_MAX_ROUNDS"), totalUsage)
}

internal suspend fun LLMServices.postUpstream(source: String, body: String, provider: LLMProvider, url: String = provider.chatCompletionsUrl) =
	client.post(url) {
		logUpstreamRequest(source, body, provider, if (url == provider.responsesUrl) "responses" else "chat-completions")
		header(HttpHeaders.Authorization, "Bearer ${provider.apiToken}")
		contentType(ContentType.Application.Json)
		debugPrompt(source, body)
		setBody(body)
	}

internal suspend fun LLMServices.postSummaryUpstream(source: String, body: String, summary: LLMSummaryConfig): Pair<Int, String> {
    val pricing=summary.pricing ?: return 503 to errorJson("pricing_unavailable","摘要模型未配置计价")
    val request=JsonParser.parseString(body).asJsonObject
    val maxOutput=request.get("max_tokens")?.asLong ?: request.get("max_output_tokens")?.asLong ?: 8192
    val estimate=pricing.cost(body.toByteArray(StandardCharsets.UTF_8).size.toLong(),maxOutput,0)
    val reserved=dailyQuotaService.reserveSubsidy(source,summary,estimate)
        ?: return 503 to errorJson("free_budget_exceeded","免费摘要预算不足")
    try {
        val result=runSummaryUpstream(client,body,summary) { outgoing ->
            println("[LLM] upstream request source=$source provider=${summary.providerName} model=${summary.model} api=${summary.protocol.wireValue}")
            debugPrompt(source,outgoing)
        }
        val usage=parseUsage(result.second)
        val cost=if(usage?.promptTokens!=null && usage.completionTokens!=null)
            pricing.cost(usage.promptTokens.toLong(),usage.completionTokens.toLong(),(usage.cacheHitTokens ?: 0).toLong())
            else if(result.first !in 200..299) java.math.BigDecimal.ZERO else null
        dailyQuotaService.settleSubsidy(reserved,cost)
        return result
    } catch(error: Exception) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dailyQuotaService.settleSubsidy(reserved,null) }
        throw error
    }
}

internal suspend fun runSummaryUpstream(
	client: HttpClient,
	body: String,
	summary: LLMSummaryConfig,
	onRequest: (String) -> Unit = {},
): Pair<Int, String> {
	if (summary.protocol == LLMProtocol.ANTHROPIC) {
		return runAnthropicUpstream(
			client, summary.endpointUrl, summary.apiToken,
			LLMAnthropicAdapter.fromChatRequest(body, JsonArray(), LLMReasoningEffort.NONE, webSearch = false, thinkingMode = summary.thinkingMode),
			maxToolRounds = 0,
			executeTool = { error("Summary requests cannot execute local tools") },
			onRequest = onRequest,
		)
	}
	val outgoing = if (summary.protocol == LLMProtocol.RESPONSES) {
		LLMResponsesAdapter.fromChatRequest(body, JsonArray(), LLMReasoningEffort.NONE, webSearch = false).toString()
	} else body
	onRequest(outgoing)
	val response = client.post(summary.endpointUrl) {
		header(HttpHeaders.Authorization, "Bearer ${summary.apiToken}")
		contentType(ContentType.Application.Json)
		setBody(outgoing)
	}
	val responseBody = response.bodyAsText()
	return response.status.value to if (response.status.isSuccess() && summary.protocol == LLMProtocol.RESPONSES) {
		LLMResponsesAdapter.toChatCompletion(responseBody)
	} else responseBody
}

internal fun LLMServices.authenticatedServerId(token: String): Int? = nodes.getServerFromToken(token).takeIf { it >= 0 }
internal fun LLMServices.decodeHeader(value: String): String = runCatching {
	URLDecoder.decode(value, StandardCharsets.UTF_8)
}.getOrDefault(value)

internal fun LLMServices.streamFromUpstream(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	requestId: Long,
	source: String,
	provider: LLMProvider,
	quotaReservation: LLMQuotaReservation,
): Flow<String> = flow {
	val functionTools = if (toolService.enabled()) toolService.definitions() else JsonArray()
	val upstreamBody = LLMWebSearchAdapter.enableChatCompletions(request.body, functionTools).toString()
	var upstreamAccepted = false
	var latestUsage: LLMServices.Usage? = null
	var lastProgressKey: String? = null
	suspend fun emitProgress(phase: String, label: String) {
		val key = "$phase:$label"
		if (key == lastProgressKey) return
		lastProgressKey = key
		emit(progressChunk(phase, label))
	}

	emitProgress("analyzing", "正在分析问题…")
	try {
		client.preparePost(provider.chatCompletionsUrl) {
			logUpstreamRequest(source, upstreamBody, provider, "chat-completions")
			header(HttpHeaders.Authorization, "Bearer ${provider.apiToken}")
			header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
			contentType(ContentType.Application.Json)
			debugPrompt(source, upstreamBody)
			setBody(upstreamBody)
		}.execute { response ->
			if (!response.status.isSuccess()) {
				val errorBody = response.bodyAsText()
				refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
				updateAccessRecord(requestId, "failed", errorMessage = errorBody.take(512), groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				emit(errorJson("upstream_error", errorBody.take(256)))
				return@execute
			}
			upstreamAccepted = true

			val assistantContent = StringBuilder()
			if (response.contentType()?.match(ContentType.Text.EventStream) != true) {
				val body = response.bodyAsText()
				val converted = nonStreamCompletionToStreamChunk(body)
				if (converted == null) {
					refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
					updateAccessRecord(requestId, "failed", errorMessage = body.take(512), groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
					emit(normalizeUpstreamError(body))
					return@execute
				}
				latestUsage = parseUsage(body)
				assistantContent.append(converted.second)
				emitProgress("generating", "正在生成回复…")
				emit(converted.first)
				val settled = settleUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
				emit(attachQuota("{}", settled))
				updateAccessRecord(requestId, "completed", latestUsage, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				recordConversationAnswer(requester, request.userContent, assistantContent.toString(), provider)
				return@execute
			}
			var completed = false
			val channel = response.bodyAsChannel()
			while (!channel.isClosedForRead) {
				val line = channel.readUTF8Line() ?: break
				if (!line.startsWith("data:")) continue
				val data = line.removePrefix("data:").trim()
				if (data.isBlank()) continue
				if (data == "[DONE]") completed = true
				if (data != "[DONE]") {
					parseUsage(data)?.let { latestUsage = it }
					val upstreamEvent = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
					if (upstreamEvent?.has("error") == true) {
						refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
						updateAccessRecord(requestId, "failed", errorMessage = data.take(512), groupName = requester.groupName, qqUid = requester.uid)
						emit(normalizeUpstreamError(data))
						return@execute
					}
					streamProgress(data)?.let { (phase, label) -> emitProgress(phase, label) }
					val assistantDelta = parseStreamAssistantContent(data)
					if (!assistantDelta.isNullOrBlank()) {
						emitProgress("generating", "正在生成回复…")
						assistantContent.append(assistantDelta)
					}
				}
				if (data != "[DONE]") emit(data)
			}
			require(completed) { "Stream ended before DONE" }
			val settled = settleUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
			emit(attachQuota("{}", settled))
			updateAccessRecord(requestId, "completed", latestUsage, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
			if (assistantContent.isNotBlank()) {
				recordConversationAnswer(requester, request.userContent, assistantContent.toString(), provider)
			}
		}
	} catch (e: Exception) {
		if (e is kotlinx.coroutines.CancellationException) throw e
		if (e !is QuotaSettlementException) refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
		updateAccessRecord(requestId, "failed", errorMessage = e.message, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
		emit(errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
	}
}

internal fun LLMServices.streamFromResponses(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	requestId: Long,
	source: String,
	provider: LLMProvider,
	quotaReservation: LLMQuotaReservation,
): Flow<String> = flow {
	val functionTools = if (toolService.enabled()) toolService.definitions() else JsonArray()
	val upstreamBody = LLMResponsesAdapter.fromChatRequest(
		request.body,
		functionTools,
		reasoningEffort = request.reasoningEffort,
		stream = true,
	)
	val assistantContent = StringBuilder()
	var totalUsage: LLMServices.Usage? = null
	var upstreamAccepted = false
	var lastProgressKey: String? = null
	suspend fun emitProgress(phase: String, label: String) {
		val key = "$phase:$label"
		if (key == lastProgressKey) return
		lastProgressKey = key
		emit(progressChunk(phase, label))
	}

	emitProgress("analyzing", "正在分析问题…")
	try {
		repeat(maxToolRounds) { round ->
			var terminalResponse: JsonObject? = null
			var upstreamError: String? = null
			client.preparePost(provider.responsesUrl) {
				val body = upstreamBody.toString()
				logUpstreamRequest("$source/responses-round-${round + 1}", body, provider, "responses")
				header(HttpHeaders.Authorization, "Bearer ${provider.apiToken}")
				header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
				contentType(ContentType.Application.Json)
				debugPrompt(source, body)
				setBody(body)
			}.execute { response ->
				if (!response.status.isSuccess()) {
					upstreamError = response.bodyAsText()
					return@execute
				}
				upstreamAccepted = true

				val channel = response.bodyAsChannel()
				while (!channel.isClosedForRead) {
					val line = channel.readUTF8Line() ?: break
					if (!line.startsWith("data:")) continue
					val data = line.removePrefix("data:").trim()
					if (data.isBlank()) continue
					val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
					when (event.get("type")?.asString) {
						"error" -> {
							upstreamError = errorJson("upstream_error", event.get("message")?.asString ?: "Responses API 请求失败")
							break
						}
						"response.reasoning_text.delta" -> {
							if (!event.get("delta")?.asString.isNullOrBlank()) {
								emitProgress("thinking", "正在整理思路…")
							}
						}

						"response.output_text.delta" -> {
							val delta = event.get("delta")?.asString.orEmpty()
							if (delta.isNotEmpty()) {
								emitProgress("generating", "正在生成回复…")
								assistantContent.append(delta)
								emit(responsesTextDeltaChunk(delta, request.model))
							}
						}

						"response.web_search_call.in_progress",
						"response.web_search_call.searching",
						"response.web_search_call.completed" ->
							emitProgress("web_search", "正在进行 Web 搜索…")

						"response.completed", "response.incomplete", "response.failed" -> {
							terminalResponse = event.getAsJsonObject("response")
						}
					}
				}
			}

			if (upstreamError != null) {
				refundUsage(quotaReservation, totalUsage, request, provider, requester.conversationId)
				updateAccessRecord(requestId, "failed", errorMessage = upstreamError!!.take(512), groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				emit(normalizeUpstreamError(upstreamError!!))
				return@flow
			}

			val completed = terminalResponse
			if (completed == null) {
				refundUsage(quotaReservation, totalUsage, request, provider, requester.conversationId)
				updateAccessRecord(requestId, "failed", errorMessage = "Responses stream ended without a terminal event", groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				emit(errorJson("upstream_error", "Responses API 流提前结束"))
				return@flow
			}
			if (completed.get("status")?.asString in setOf("failed", "incomplete")) {
				refundUsage(quotaReservation, accumulateUsage(totalUsage, parseUsage(completed.toString())), request, provider, requester.conversationId)
				val message = completed.getAsJsonObject("error")?.get("message")?.asString
					?: "Responses API 请求失败"
				updateAccessRecord(requestId, "failed", errorMessage = message.take(512), groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				emit(errorJson("upstream_error", message))
				return@flow
			}

			val completedBody = completed.toString()
			totalUsage = accumulateUsage(totalUsage, parseUsage(completedBody))
			val functionCalls = LLMResponsesAdapter.functionCalls(completedBody)
			if (functionCalls.isEmpty()) {
				val usage = totalUsage
				if (assistantContent.isBlank()) {
					val converted = nonStreamCompletionToStreamChunk(
						LLMResponsesAdapter.toChatCompletion(completedBody)
					)
					if (converted != null && converted.second.isNotBlank()) {
						emitProgress("generating", "正在生成回复…")
						assistantContent.append(converted.second)
						emit(converted.first)
					}
				}
				val settled = settleUsage(quotaReservation, usage, request, provider, requester.conversationId)
				emit(attachQuota("{}", settled))
				updateAccessRecord(requestId, "completed", usage, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				if (assistantContent.isNotBlank()) {
					recordConversationAnswer(requester, request.userContent, assistantContent.toString(), provider)
				}
				return@flow
			}

			val outputs = linkedMapOf<String, String>()
			for (call in functionCalls) {
				val (phase, label) = toolProgress(call.name)
				emitProgress(phase, label)
				outputs[call.callId] = toolService.execute(
					call.name,
					call.arguments,
					requester.toolContext(request.currentUserText),
				)
			}
			LLMResponsesAdapter.appendToolOutputs(upstreamBody, completedBody, outputs)
		}

		refundUsage(quotaReservation, totalUsage, request, provider, requester.conversationId)
		updateAccessRecord(requestId, "failed", errorMessage = "Responses tool round limit exceeded", groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
		emit(errorJson("tool_round_limit", "工具调用轮数超过限制，请调高 LLM_TOOL_MAX_ROUNDS"))
	} catch (e: Exception) {
		if (e is kotlinx.coroutines.CancellationException) throw e
		if (e !is QuotaSettlementException) refundUsage(quotaReservation, totalUsage, request, provider, requester.conversationId)
		updateAccessRecord(requestId, "failed", errorMessage = e.message, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
		emit(errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
	}
}

internal fun LLMServices.responsesTextDeltaChunk(delta: String, model: String): String = JsonObject().apply {
	addProperty("object", "chat.completion.chunk")
	addProperty("model", model)
	add("choices", JsonArray().apply {
		add(JsonObject().apply {
			addProperty("index", 0)
			add("delta", JsonObject().apply {
				addProperty("role", "assistant")
				addProperty("content", delta)
			})
		})
	})
}.toString()

internal fun LLMServices.progressChunk(phase: String, label: String): String = JsonObject().apply {
	addProperty("object", "kotshi.status")
	addProperty("phase", phase)
	addProperty("label", label)
}.toString()

internal fun LLMServices.nonStreamCompletionToStreamChunk(body: String): Pair<String, String>? = runCatching {
	val root = JsonParser.parseString(body).asJsonObject
	val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
		?: error("upstream response has no choices")
	val message = choice.getAsJsonObject("message")
		?: error("upstream response has no assistant message")
	val content = message.get("content")
		?.takeIf { !it.isJsonNull }
		?.let(::extractTextContent)
		?: error("upstream response has no assistant content")
	val chunk = JsonObject().apply {
		root.get("id")?.let { add("id", it.deepCopy()) }
		addProperty("object", "chat.completion.chunk")
		root.get("created")?.let { add("created", it.deepCopy()) }
		root.get("model")?.let { add("model", it.deepCopy()) }
		add("choices", JsonArray().apply {
			add(JsonObject().apply {
				add("index", choice.get("index")?.deepCopy() ?: JsonPrimitive(0))
				add("delta", JsonObject().apply {
					addProperty("role", message.get("role")?.asString ?: "assistant")
					addProperty("content", content)
				})
				add("finish_reason", choice.get("finish_reason")?.deepCopy())
			})
		})
		root.get("usage")?.let { add("usage", it.deepCopy()) }
	}
	chunk.toString() to content
}.getOrNull()

internal fun LLMServices.extractConversationId(body: String): String? = runCatching {
	val obj = JsonParser.parseString(body).asJsonObject
	(obj.get("conversation_id") ?: obj.get("conversationId"))
		?.takeIf { !it.isJsonNull }
		?.asString
		?.trim()
		?.takeIf { it.isNotEmpty() }
}.getOrNull()

internal fun LLMServices.normalizeUpstreamError(body: String): String = runCatching {
	val root = JsonParser.parseString(body).asJsonObject
	if (root.has("error")) root.toString()
	else errorJson("upstream_error", body.take(256))
}.getOrElse { errorJson("upstream_error", body.take(256)) }
