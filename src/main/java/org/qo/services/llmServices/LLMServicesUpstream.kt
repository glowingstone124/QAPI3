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
	if (request.clientTools != null) return completeClientTools(request, source, provider)
	if (provider.protocol(request.preset) == LLMProtocol.COMMANDCODE) {
		val attempt = completeWithCommandCodeApi(request, requester, source, provider)
		return attempt.status to attempt.body
	}
	if (provider.protocol(request.preset) == LLMProtocol.ANTHROPIC) {
		return completeWithAnthropicApi(request, requester, source, provider)
	}
	if (provider.supportsResponses(request.preset)) {
		return completeWithResponsesApi(request, requester, source, provider)
	}
	val functionTools = toolService.definitions()
	val obj = LLMAdapterRegistry.forProtocol(LLMProtocol.CHAT_COMPLETIONS).adapt(
		LLMAdapterRequest(
			chat = JsonParser.parseString(request.body).asJsonObject,
			functionTools = functionTools,
			reasoningEffort = request.reasoningEffort,
			webSearch = !toolService.usesRemoteSearch(),
		)
	)

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
	val functionTools = toolService.definitions()
	val body = LLMAdapterRegistry.forProtocol(LLMProtocol.RESPONSES).adapt(
		LLMAdapterRequest(
			chat = JsonParser.parseString(request.body).asJsonObject,
			functionTools = functionTools,
			reasoningEffort = request.reasoningEffort,
			webSearch = !toolService.usesRemoteSearch(),
		)
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
    val pricing=summary.pricing?.at(java.time.Instant.now()) ?: return 503 to errorJson("pricing_unavailable","摘要模型未配置计价")
    val request=JsonParser.parseString(body).asJsonObject
    val maxOutput=request.get("max_tokens")?.asLong ?: request.get("max_output_tokens")?.asLong ?: 8192
    val estimate=pricing.cost(body.toByteArray(StandardCharsets.UTF_8).size.toLong(),maxOutput,0)
    val reserved=dailyQuotaService.reserveSubsidy(source,summary,estimate)
        ?: return 503 to errorJson("quota_unavailable","摘要成本记录服务暂时不可用")
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
	val chat = JsonParser.parseString(body).asJsonObject
	if (summary.protocol == LLMProtocol.COMMANDCODE) {
		return runCommandCodeUpstream(
			client, summary.endpointUrl, summary.apiToken,
			LLMAdapterRegistry.forProtocol(LLMProtocol.COMMANDCODE).adapt(
				LLMAdapterRequest(chat, JsonArray(), LLMReasoningEffort.NONE, webSearch = false)
			),
			onRequest = onRequest,
		)
	}
	if (summary.protocol == LLMProtocol.ANTHROPIC) {
		return runAnthropicUpstream(
			client, summary.endpointUrl, summary.apiToken,
			LLMAdapterRegistry.forProtocol(LLMProtocol.ANTHROPIC).adapt(
				LLMAdapterRequest(
					chat,
					JsonArray(),
					LLMReasoningEffort.NONE,
					webSearch = false,
					thinkingMode = summary.thinkingMode,
				)
			),
			maxToolRounds = 0,
			executeTool = { error("Summary requests cannot execute local tools") },
			onRequest = onRequest,
		)
	}
	val outgoing = if (summary.protocol == LLMProtocol.RESPONSES) {
		LLMAdapterRegistry.forProtocol(LLMProtocol.RESPONSES).adapt(
			LLMAdapterRequest(chat, JsonArray(), LLMReasoningEffort.NONE, webSearch = false)
		).toString()
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
