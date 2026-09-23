package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow

internal suspend fun LLMServices.completeClientTools(
	request: LLMServices.NormalizedRequest, source: String, provider: LLMProvider,
	onCommandCodeUpdate: suspend (CommandCodeUpdate) -> Unit = {},
): Pair<Int, String> {
	val protocol = provider.protocol(request.preset)
	val chat = clientToolStepChat(JsonParser.parseString(request.body).asJsonObject)
	// DeepSeek requires hidden reasoning history for thinking tool continuations.
	if (protocol == LLMProtocol.CHAT_COMPLETIONS &&
		(provider.name.contains("deepseek", true) || chat.get("model")?.asString?.contains("deepseek", true) == true)
	) {
		chat.add("thinking", JsonObject().apply { addProperty("type", "disabled") })
		chat.addProperty("reasoning_effort", "none")
	}
	val effort = configureClientToolStep(chat, protocol, request.reasoningEffort)
	val (status, body) = runClientToolAgentTurn(
		client, provider.endpoint(protocol), provider.apiToken, protocol,
		chat, requireNotNull(request.clientTools), effort,
		provider.contextWindow,
		onRequest = { logUpstreamRequest(source, it, provider, protocol.wireValue); debugPrompt(source, it) },
		onCommandCodeUpdate = onCommandCodeUpdate
	)
	if (status !in 200..299) return status to body
	// Only expose the native assistant content/calls, never provider reasoning.
	val root = limitClientToolStep(JsonParser.parseString(body).asJsonObject)
	val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: error("模型缺少 choices")
	val finishReason = choice.get("finish_reason")?.asString
	require(finishReason in setOf("stop", "tool_calls")) { clientToolFinishError(finishReason) }
	val message = choice.getAsJsonObject("message") ?: error("模型缺少 message")
	message.remove("reasoning_content"); message.remove("reasoning")
	message.getAsJsonArray("tool_calls")?.let { calls ->
		require(calls.size() in 1..256 && calls.toString().length <= 128 * 1024) { "模型工具调用过多或过大" }
		val ids = mutableSetOf<String>()
		calls.forEach { item ->
			val call = item.asJsonObject
			val name = call.getAsJsonObject("function")?.get("name")?.asString?.trim()
			require(
				call.get("type")?.asString == "function" &&
					!name.isNullOrBlank() && name.length <= 128
			) { "模型返回无效客户端工具调用" }
			val id = call.get("id")?.asString.orEmpty()
			require(id.isNotBlank() && id.length <= 256 && ids.add(id)) { "模型返回无效工具 ID" }
		}
	}
	return status to root.toString()
}

internal fun clientToolFinishError(reason: String?): String = when (reason) {
	"length" -> "建筑操作输出达到模型长度上限，请缩小本轮操作范围后重试"
	"content_filter" -> "模型未完成建筑操作，请调整请求后重试"
	null -> "模型未返回完整的建筑操作结束标记，请重试"
	else -> "模型中断了建筑操作（$reason），请重试"
}

internal fun LLMServices.streamClientTools(
	request: LLMServices.NormalizedRequest, requester: LLMServices.LLMRequester, requestId: Long,
	provider: LLMProvider, reservation: LLMQuotaReservation,
) = flow {
	var usage: LLMServices.Usage? = null
	try {
		emit(progressChunk("analyzing", "正在规划建筑操作…"))
		var lastProgressAt = 0L
		var lastPhase: String? = null
		val (status, body) = completeClientTools(
			request, "client-tools", provider,
			onCommandCodeUpdate = { update ->
				val phase = update.phase
				val now = System.currentTimeMillis()
				if (phase != null && (phase != lastPhase || now - lastProgressAt >= 15_000)) {
					emit(
						progressChunk(
							if (phase == "thinking") "analyzing" else "generating",
							if (phase == "thinking") "模型正在规划下一步建筑操作…" else "模型正在生成下一项建筑工具…"
						)
					)
					lastPhase = phase
					lastProgressAt = now
				}
			})
		usage = parseUsage(body)
		if (status !in 200..299) {
			refundUsage(reservation, usage, request, provider, requester.conversationId)
			updateAccessRecord(requestId, "failed", errorMessage = body.take(512), qqUid = requester.uid)
			emit(normalizeUpstreamError(body)); return@flow
		}
		val settled = settleUsage(reservation, usage, request, provider, requester.conversationId)
		// Buffered native completion: the client executes only after quota + [DONE].
		emit(body)
		emit(attachQuota("{}", settled))
		updateAccessRecord(requestId, "completed", usage, qqUid = requester.uid)
		val message =
			JsonParser.parseString(body).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
		if (!message.has("tool_calls")) extractAssistantContent(body)?.let {
			recordConversationAnswer(
				requester,
				request.userContent,
				it,
				provider
			)
		}
	} catch (error: Exception) {
		if (error is CancellationException) throw error
		if (error !is QuotaSettlementException) refundUsage(
			reservation,
			usage,
			request,
			provider,
			requester.conversationId
		)
		updateAccessRecord(requestId, "failed", errorMessage = error.message, qqUid = requester.uid)
		val timedOut = error is HttpRequestTimeoutException || error is java.net.SocketTimeoutException
		emit(
			errorJson(
				if (timedOut) "client_tool_timeout" else "client_tool_error",
				if (timedOut) "模型服务响应超时，本步建筑工具未执行；请重试" else error.message ?: "客户端工具请求失败"
			)
		)
	}
}
