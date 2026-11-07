package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal fun buildGoogleEndpoint(endpointUrl: String, model: String, stream: Boolean = false): String {
	val cleanModel = model.trim().removePrefix("models/")
	require(cleanModel.isNotBlank()) { "Google model name must not be blank" }
	val trimmedUrl = endpointUrl.trim()
	val rawPath = trimmedUrl.substringBefore('?')
	val rawQuery = trimmedUrl.substringAfter('?', "")
	val action = if (stream) "streamGenerateContent" else "generateContent"
	val replaced = rawPath.replace("{model}", cleanModel)
	val fullPath = when {
		replaced.endsWith(":generateContent") -> replaced.removeSuffix(":generateContent") + ":$action"
		replaced.endsWith(":streamGenerateContent") -> replaced.removeSuffix(":streamGenerateContent") + ":$action"
		rawPath.contains("{model}") -> "${replaced.trimEnd('/')}:$action"
		replaced.trimEnd('/').endsWith("/models") -> "${replaced.trimEnd('/')}/$cleanModel:$action"
		else -> "${replaced.trimEnd('/')}/models/$cleanModel:$action"
	}
	val queryParts = rawQuery.split('&').filter { it.isNotBlank() }.toMutableList()
	if (stream && queryParts.none { it.startsWith("alt=") }) {
		queryParts.add("alt=sse")
	}
	return if (queryParts.isEmpty()) fullPath else "$fullPath?${queryParts.joinToString("&")}"
}

internal fun applyGoogleAuthHeaders(builder: HttpMessageBuilder, apiToken: String) {
	if (apiToken.startsWith("ya29.")) {
		builder.header(HttpHeaders.Authorization, "Bearer $apiToken")
	} else {
		builder.header("x-goog-api-key", apiToken)
	}
}

/** Shared round orchestration for Google Gemini streaming, non-streaming, and summary requests. */
internal suspend fun runGoogleUpstream(
	client: HttpClient,
	endpointUrl: String,
	apiToken: String,
	model: String,
	request: JsonObject,
	maxToolRounds: Int,
	stream: Boolean = false,
	executeTool: suspend (ResponseFunctionCall) -> String,
	onUpdate: suspend (GoogleStreamUpdate) -> Unit = {},
	onAccepted: () -> Unit = {},
	onRequest: (String) -> Unit = {},
	onIntermediateText: (suspend (String, List<ResponseFunctionCall>) -> Unit)? = null,
): Pair<Int, String> {
	val totalUsage = JsonObject()
	fun failure(body: String): String = if (!totalUsage.has("qapi_api_calls")) body else runCatching {
		val converted = JsonParser.parseString(LLMGoogleAdapter.toChatCompletion(JsonObject().apply {
			add("candidates", JsonArray())
			add("usageMetadata", totalUsage)
		}, model)).asJsonObject
		JsonParser.parseString(body).asJsonObject.apply { add("usage", converted.get("usage")) }.toString()
	}.getOrDefault(body)

	var toolRounds = 0
	val targetUrl = buildGoogleEndpoint(endpointUrl, model, stream)
	while (true) {
		var completed: JsonObject? = null
		var status = 502
		var errorBody: String? = null
		val body = request.toString()
		onRequest(body)
		client.preparePost(targetUrl) {
			applyGoogleAuthHeaders(this, apiToken)
			if (stream) {
				header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
			}
			contentType(ContentType.Application.Json)
			setBody(body)
		}.execute { response ->
			status = response.status.value
			if (!response.status.isSuccess()) {
				errorBody = response.bodyAsText()
				return@execute
			}
			onAccepted()
			if (response.contentType()?.match(ContentType.Text.EventStream) == true) {
				val state = LLMGoogleStreamState()
				val channel = response.bodyAsChannel()
				val dataLines = mutableListOf<String>()
				suspend fun flushEvent() {
					if (dataLines.isEmpty()) return
					val data = dataLines.joinToString("\n").trim()
					dataLines.clear()
					if (data.isEmpty() || data == "[DONE]") return
					onUpdate(state.accept(JsonParser.parseString(data).asJsonObject))
				}
				while (true) {
					val line = channel.readUTF8Line() ?: break
					if (line.isEmpty()) flushEvent()
					else if (line.startsWith("data:")) dataLines.add(line.removePrefix("data:").removePrefix(" "))
				}
				flushEvent()
				completed = state.finish()
			} else {
				val parsed = JsonParser.parseString(response.bodyAsText())
				completed = if (parsed.isJsonArray) {
					val state = LLMGoogleStreamState()
					parsed.asJsonArray.forEach { chunk ->
						val update = state.accept(chunk.asJsonObject)
						if (stream) onUpdate(update)
					}
					state.finish()
				} else {
					parsed.asJsonObject.also { single ->
						if (stream) {
							val text = LLMGoogleAdapter.text(single)
							if (text.isNotEmpty()) onUpdate(GoogleStreamUpdate(text, "generating"))
						}
					}
				}
			}
		}
		errorBody?.let { return status to failure(it) }
		val result = requireNotNull(completed)

		totalUsage.addProperty("qapi_api_calls", (totalUsage.get("qapi_api_calls")?.asInt ?: 0) + 1)
		val roundUsage = result.getAsJsonObject("usageMetadata")
		totalUsage.addProperty(
			"qapi_usage_complete",
			(totalUsage.get("qapi_usage_complete")?.asBoolean ?: true) &&
				roundUsage != null &&
				(roundUsage.get("promptTokenCount")?.isJsonPrimitive == true ||
					roundUsage.get("totalTokenCount")?.isJsonPrimitive == true),
		)
		roundUsage?.let { usage ->
			listOf(
				"promptTokenCount",
				"toolUsePromptTokenCount",
				"candidatesTokenCount",
				"thoughtsTokenCount",
				"cachedContentTokenCount",
				"totalTokenCount",
			).forEach { key ->
				if (usage.has(key) && !usage.get(key).isJsonNull) {
					totalUsage.addProperty(key, (totalUsage.get(key)?.asInt ?: 0) + usage.get(key).asInt)
				}
			}
		}

		LLMGoogleAdapter.blockError(result)?.let { message ->
			val code = if (message.startsWith("Google prompt blocked:")) "content_filter" else "upstream_error"
			return 502 to failure(googleError(code, message))
		}

		val calls = LLMGoogleAdapter.functionCalls(result)
		if (calls.isNotEmpty()) {
			val roundText = LLMGoogleAdapter.text(result).trim().takeIf { it.isNotBlank() }
			if (roundText != null) {
				onIntermediateText?.invoke(roundText, calls)
			}
			if (toolRounds++ >= maxToolRounds) {
				return 502 to failure(googleError("tool_round_limit", "工具调用轮数超过限制"))
			}
			val outputs = linkedMapOf<String, String>()
			for (call in calls) {
				outputs[call.callId] = executeTool(call)
			}
			LLMGoogleAdapter.appendContinuation(request, result, outputs)
		} else {
			require(result.getAsJsonArray("candidates")?.size()?.let { it > 0 } == true) {
				"Google upstream returned no candidates"
			}
			result.add("usageMetadata", totalUsage)
			return status to LLMGoogleAdapter.toChatCompletion(result, model)
		}
	}
}

private fun googleError(code: String, message: String): String = JsonObject().apply {
	add("error", JsonObject().apply {
		addProperty("type", code)
		addProperty("code", code)
		addProperty("message", message)
	})
}.toString()

internal suspend fun LLMServices.completeWithGoogleApi(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	source: String,
	provider: LLMProvider,
): Pair<Int, String> {
	val body = adaptUpstreamRequest(
		request,
		LLMProtocol.GOOGLE,
		thinkingMode = provider.modelConfig(request.preset).thinkingMode,
		source = requester.source,
	)
	val intermediateMessages = mutableListOf<String>()
	val (status, text) = runGoogleUpstream(
		client = client,
		endpointUrl = provider.endpoint(LLMProtocol.GOOGLE),
		apiToken = provider.apiToken,
		model = request.model,
		request = body,
		maxToolRounds = maxToolRounds,
		stream = false,
		executeTool = { call ->
			toolService.execute(
				call.name,
				call.arguments,
				requester.executingToolContext(request.currentUserText),
			)
		},
		onIntermediateText = { rawRoundText, calls ->
			recordIntermediateMessage(
				rawRoundText,
				request.enableMarkdown,
				calls.any { it.name == "generate_image" },
				requester,
				intermediateMessages,
			)
		},
		onRequest = { outgoing ->
			logUpstreamRequest(source, outgoing, provider, "google", request.model)
			debugPrompt(source, outgoing)
		},
	)
	val merged = mergeIntermediateMessages(text, intermediateMessages, request.botReplyMessages)
	return status to if (status in 200..299) sanitizeResponseBody(merged, request.enableMarkdown) else merged
}

internal fun LLMServices.streamFromGoogle(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	requestId: Long,
	source: String,
	provider: LLMProvider,
	quotaReservation: LLMQuotaReservation,
): Flow<String> = flow {
	var lastPhase: String? = null
	val assistant = StringBuilder()
	suspend fun progress(phase: String, label: String) {
		if (phase == lastPhase) return
		lastPhase = phase
		emit(progressChunk(phase, label))
	}

	suspend fun update(update: GoogleStreamUpdate) {
		update.phase?.let { phase ->
			progress(
				phase,
				when (phase) {
					"thinking" -> "正在整理思路…"
					"web_search" -> "正在进行 Web 搜索…"
					else -> "正在生成回复…"
				},
			)
		}
		if (update.text.isNotEmpty()) {
			assistant.append(update.text)
			emit(responsesTextDeltaChunk(update.text, request.model))
		}
	}

	progress("analyzing", "正在分析问题…")
	try {
		val body = adaptUpstreamRequest(
			request,
			LLMProtocol.GOOGLE,
			stream = true,
			thinkingMode = provider.modelConfig(request.preset).thinkingMode,
			source = requester.source,
		)
		val (status, text) = runGoogleUpstream(
			client = client,
			endpointUrl = provider.endpoint(LLMProtocol.GOOGLE),
			apiToken = provider.apiToken,
			model = request.model,
			request = body,
			maxToolRounds = maxToolRounds,
			stream = true,
			executeTool = { call ->
				val (phase, label) = toolProgress(call.name)
				progress(phase, label)
				toolService.execute(call.name, call.arguments, requester.executingToolContext(request.currentUserText))
			},
			onUpdate = { update(it) },
			onRequest = { outgoing ->
				logUpstreamRequest(source, outgoing, provider, "google", request.model)
				debugPrompt(source, outgoing)
			},
		)
		val groupLabel = requester.groupName ?: requester.groupId?.let { "group:$it" }
		if (status !in 200..299) {
			refundUsage(quotaReservation, parseUsage(text), request, provider, requester.conversationId)
			updateAccessRecord(
				requestId,
				"failed",
				errorMessage = text.take(512),
				groupName = groupLabel,
				qqUid = requester.uid,
			)
			emit(normalizeUpstreamError(text))
			return@flow
		}
		// Text deltas were already emitted; send only the terminal finish_reason and cumulative usage.
		nonStreamCompletionToStreamChunk(text)?.first?.let { chunk ->
			val terminal = JsonParser.parseString(chunk).asJsonObject
			terminal.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta").remove("content")
			emit(terminal.toString())
		}
		val usage = parseUsage(text)
		val settled = settleUsage(quotaReservation, usage, request, provider, requester.conversationId)
		emit(attachQuota("{}", settled))
		updateAccessRecord(
			requestId,
			"completed",
			usage,
			groupName = groupLabel,
			qqUid = requester.uid,
		)
		if (assistant.isNotBlank()) {
			recordConversationAnswer(
				requester,
				request.userContent,
				assistant.toString(),
				provider,
			)
		} else {
			LLMEmptyResponseLog.record(
				stage = "$source/google-stream",
				request = request,
				requester = requester,
				provider = provider.name,
				model = request.model,
				statusCode = 200,
				rawResponse = text,
				reason = "empty_google_stream_content",
				accessRecordId = requestId,
			)
		}
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		LLMErrorLog.record("$source/google-stream", e, provider.name, requester, requestId)
		if (e !is QuotaSettlementException) dailyQuotaService.refund(quotaReservation)
		updateAccessRecord(
			requestId,
			"failed",
			errorMessage = e.message,
			groupName = requester.groupName ?: requester.groupId?.let { "group:$it" },
			qqUid = requester.uid,
		)
		emit(errorJson("upstream_error", e.message ?: "Google 上游请求失败"))
	}
}
