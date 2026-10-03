package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun LLMServices.adaptUpstreamRequest(
	request: LLMServices.NormalizedRequest,
	protocol: LLMProtocol,
	stream: Boolean = false,
	thinkingMode: String = "enabled",
	source: String? = null,
): JsonObject = LLMAdapterRegistry.forProtocol(protocol).adapt(
	LLMAdapterRequest(
		chat = JsonParser.parseString(request.body).asJsonObject,
		functionTools = toolService.definitions(source = source) ?: JsonArray(),
		reasoningEffort = request.reasoningEffort,
		stream = stream,
		webSearch = !toolService.usesRemoteSearch(),
		thinkingMode = thinkingMode,
	)
)

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

	val obj = adaptUpstreamRequest(request, LLMProtocol.CHAT_COMPLETIONS, source = requester.source)

	var latestStatus = 502
	var latestBody = ""
	var totalUsage: LLMServices.Usage? = null
	val intermediateMessages = mutableListOf<String>()

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
				toolService.logInvalidToolCall(
					latestBody, requester.toolContext(request.currentUserText), source,
					provider.name, request.model, round + 1,
				)
				return 502 to withUsage(errorJson("invalid_tool_call", "LLM 输出了无法解析的工具调用"), totalUsage)
			}
			val mergedBody = mergeIntermediateMessages(latestBody, intermediateMessages, request.botReplyMessages)
			return latestStatus to sanitizeResponseBody(withUsage(mergedBody, totalUsage), request.enableMarkdown)
		}

		val rawRoundText = extractAssistantContent(latestBody)?.trim()?.takeIf { it.isNotBlank() }
		val roundText = rawRoundText?.let { sanitizeAssistantText(it, request.enableMarkdown).trim() }?.takeIf { it.isNotBlank() }
		if (roundText != null) {
			val hasImageCall = toolCalls.any { it.name == "generate_image" }
			var sentRealtime = false
			if (hasImageCall && requester.groupId != null) {
				val imgCfg = providers.current().imageGeneration
				val botEndpoint = imgCfg?.qbotEndpoint ?: System.getenv("QBOT_ENDPOINT")?.trim().orEmpty()
				val botToken = imgCfg?.qbotToken ?: System.getenv("QBOT_TOKEN")?.trim().orEmpty()
				if (botEndpoint.isNotBlank() && botToken.isNotBlank()) {
					sentRealtime = sendBotTextMessage(botEndpoint, botToken, requester.groupId, roundText)
				}
			}
			if (!sentRealtime) {
				intermediateMessages.add(roundText)
			}
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
	val body = adaptUpstreamRequest(request, LLMProtocol.RESPONSES, source = requester.source)
	var totalUsage: LLMServices.Usage? = null
	val intermediateMessages = mutableListOf<String>()

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
			val completionBody = LLMResponsesAdapter.toChatCompletion(responseText)
			val mergedBody = mergeIntermediateMessages(completionBody, intermediateMessages, request.botReplyMessages)
			return response.status.value to sanitizeResponseBody(
				withUsage(mergedBody, totalUsage),
				request.enableMarkdown,
			)
		}
		val rawRoundText = LLMResponsesAdapter.extractText(responseRoot).trim().takeIf { it.isNotBlank() }
		val roundText = rawRoundText?.let { sanitizeAssistantText(it, request.enableMarkdown).trim() }?.takeIf { it.isNotBlank() }
		if (roundText != null) {
			val hasImageCall = functionCalls.any { it.name == "generate_image" }
			var sentRealtime = false
			if (hasImageCall && requester.groupId != null) {
				val imgCfg = providers.current().imageGeneration
				val botEndpoint = imgCfg?.qbotEndpoint ?: System.getenv("QBOT_ENDPOINT")?.trim().orEmpty()
				val botToken = imgCfg?.qbotToken ?: System.getenv("QBOT_TOKEN")?.trim().orEmpty()
				if (botEndpoint.isNotBlank() && botToken.isNotBlank()) {
					sentRealtime = sendBotTextMessage(botEndpoint, botToken, requester.groupId, roundText)
				}
			}
			if (!sentRealtime) {
				intermediateMessages.add(roundText)
			}
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

internal suspend fun LLMServices.sendBotTextMessage(
	botEndpoint: String,
	botToken: String,
	groupId: Long,
	text: String,
): Boolean {
	if (botEndpoint.isBlank() || botToken.isBlank() || text.isBlank()) return false
	return try {
		val payload = JsonObject().apply {
			addProperty("group_id", groupId)
			addProperty("text", text)
			add("message", JsonArray().apply {
				add(JsonObject().apply {
					addProperty("type", "text")
					add("data", JsonObject().apply {
						addProperty("text", text)
					})
				})
			})
		}.toString()
		val response = client.post("${botEndpoint.removeSuffix("/")}/action/send_message") {
			header(HttpHeaders.Authorization, botToken)
			contentType(ContentType.Application.Json)
			setBody(payload)
		}
		response.status.isSuccess()
	} catch (ex: Exception) {
		println("[LLM] failed to send intermediate bot text message: ${ex.message}")
		false
	}
}

internal fun mergeIntermediateMessages(
	responseBody: String,
	intermediateMessages: List<String>,
	useBreakMarker: Boolean,
): String {
	if (intermediateMessages.isEmpty()) return responseBody
	return runCatching {
		val root = JsonParser.parseString(responseBody).asJsonObject
		val choices = root.getAsJsonArray("choices") ?: return responseBody
		if (choices.size() == 0) return responseBody
		val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return responseBody
		val finalContent = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
		val delimiter = if (useBreakMarker) "\n${LLMBotReplyFormat.BREAK}\n" else "\n\n"
		val combined = (intermediateMessages + listOfNotNull(finalContent.takeIf { it.isNotEmpty() }))
			.joinToString(delimiter)
		message.addProperty("content", combined)
		root.toString()
	}.getOrDefault(responseBody)
}

