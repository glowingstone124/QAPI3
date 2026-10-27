package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Buffer each attempt so a failed upstream cannot leak text or client tool calls before failover. */
internal fun LLMServices.streamWithFallback(
    request: LLMServices.NormalizedRequest,
    requester: LLMServices.LLMRequester,
    requestId: Long,
    provider: LLMProvider,
    reservation: LLMQuotaReservation,
) = flow {
    emit(progressChunk("analyzing", "正在分析问题…"))
    var completed = false
    try {
        val nonStreaming = JsonParser.parseString(request.body).asJsonObject.apply {
            addProperty("stream", false)
            remove("stream_options")
        }
        val (status, body, actualRequest, actualProvider) = completeWithFallback(
            request.copy(body = nonStreaming.toString()), requester, "stream", provider,
        )
        val usage = parseUsage(body)
        if (status !in 200..299) {
            refundUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId)
            updateAccessRecord(requestId, "failed", errorMessage = body.take(512), groupName = requester.groupName, qqUid = requester.uid)
            emit(normalizeUpstreamError(body))
            return@flow
        }
        val output = if (request.clientTools != null) body else
            nonStreamCompletionToStreamChunk(body)?.first ?: error("LLM 返回了无效的回复")
        val settled = settleUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId)
        completed = true
        emit(output)
        emit(attachQuota("{}", settled))
        updateAccessRecord(requestId, "completed", usage, groupName = requester.groupName, qqUid = requester.uid)
        val answer = extractAssistantContent(body)
        if (!answer.isNullOrBlank() && (request.clientTools == null || extractToolCalls(body).isEmpty())) {
            recordConversationAnswer(requester, request.userContent, answer, actualProvider)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LLMErrorLog.record("stream/failover", error, provider.name, requester, requestId)
        if (!completed && error !is QuotaSettlementException) withContext(NonCancellable) { dailyQuotaService.refund(reservation) }
        updateAccessRecord(requestId, "failed", errorMessage = error.message, groupName = requester.groupName, qqUid = requester.uid)
        emit(errorJson("upstream_error", error.message ?: "LLM 上游请求失败"))
    }
}
