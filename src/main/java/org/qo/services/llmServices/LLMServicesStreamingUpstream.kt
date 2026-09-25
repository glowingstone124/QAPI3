package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal fun LLMServices.streamFromUpstream(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	requestId: Long,
	source: String,
	provider: LLMProvider,
	quotaReservation: LLMQuotaReservation,
): Flow<String> = flow {
	val functionTools = toolService.definitions()
	val upstreamBody = LLMAdapterRegistry.forProtocol(LLMProtocol.CHAT_COMPLETIONS).adapt(
		LLMAdapterRequest(
			chat = JsonParser.parseString(request.body).asJsonObject,
			functionTools = functionTools,
			reasoningEffort = request.reasoningEffort,
			webSearch = !toolService.usesRemoteSearch(),
		)
	).toString()
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
	if (toolService.usesRemoteSearch()) {
		try {
			val nonStreamingBody = JsonParser.parseString(request.body).asJsonObject.apply {
				addProperty("stream", false)
				remove("stream_options")
			}.toString()
			val (status, body) = completeWithOptionalTools(request.copy(body = nonStreamingBody), requester, source, provider)
			latestUsage = parseUsage(body)
			if (status !in 200..299) {
				refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
				updateAccessRecord(requestId, "failed", errorMessage = body.take(512), groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
				emit(normalizeUpstreamError(body))
				return@flow
			}
			val converted = nonStreamCompletionToStreamChunk(body)
			if (converted == null) {
				refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
				updateAccessRecord(requestId, "failed", errorMessage = "Invalid chat completion", groupName = requester.groupName, qqUid = requester.uid)
				emit(errorJson("upstream_error", "LLM 返回了无效的回复"))
				return@flow
			}
			emitProgress("generating", "正在生成回复…")
			emit(converted.first)
			val settled = settleUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
			emit(attachQuota("{}", settled))
			updateAccessRecord(requestId, "completed", latestUsage, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
			if (converted.second.isNotBlank()) recordConversationAnswer(requester, request.userContent, converted.second, provider)
		} catch (error: Exception) {
			if (error is kotlinx.coroutines.CancellationException) throw error
			if (error !is QuotaSettlementException) refundUsage(quotaReservation, latestUsage, request, provider, requester.conversationId)
			updateAccessRecord(requestId, "failed", errorMessage = error.message, groupName = requester.groupName ?: requester.groupId?.let { "group:$it" }, qqUid = requester.uid)
			emit(errorJson("upstream_error", error.message ?: "LLM 上游请求失败"))
		}
		return@flow
	}
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
	val functionTools = toolService.definitions()
	val upstreamBody = LLMAdapterRegistry.forProtocol(LLMProtocol.RESPONSES).adapt(
		LLMAdapterRequest(
			chat = JsonParser.parseString(request.body).asJsonObject,
			functionTools = functionTools,
			reasoningEffort = request.reasoningEffort,
			stream = true,
			webSearch = !toolService.usesRemoteSearch(),
		)
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
