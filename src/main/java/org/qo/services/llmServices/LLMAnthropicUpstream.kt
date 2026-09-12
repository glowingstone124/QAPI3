package org.qo.services.llmServices

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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line

/** Shared round orchestration for streaming, non-streaming and summary requests. */
internal suspend fun runAnthropicUpstream(
    client: HttpClient,
    url: String,
    apiToken: String,
    request: JsonObject,
    maxToolRounds: Int,
    executeTool: suspend (ResponseFunctionCall) -> String,
    onUpdate: suspend (AnthropicStreamUpdate) -> Unit = {},
    onAccepted: () -> Unit = {},
    onRequest: (String) -> Unit = {},
): Pair<Int, String> {
    val totalUsage = JsonObject()
    var toolRounds = 0
    var pauses = 0
    while (true) {
        var completed: JsonObject? = null
        var status = 502
        var errorBody: String? = null
        val body = request.toString()
        onRequest(body)
        client.preparePost(url) {
            header("x-api-key", apiToken)
            header("anthropic-version", "2023-06-01")
            if (request.get("stream")?.asBoolean == true) header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
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
                val state = LLMAnthropicStreamState()
                val channel = response.bodyAsChannel()
                val dataLines = mutableListOf<String>()
                suspend fun flushEvent() {
                    if (dataLines.isEmpty()) return
                    val data = dataLines.joinToString("\n")
                    dataLines.clear()
                    onUpdate(state.accept(JsonParser.parseString(data).asJsonObject))
                }
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isEmpty()) flushEvent()
                    else if (line.startsWith("data:")) dataLines.add(line.removePrefix("data:").removePrefix(" "))
                }
                flushEvent()
                completed = state.completed ?: throw IllegalStateException("Anthropic stream ended without message_stop")
            } else {
                completed = JsonParser.parseString(response.bodyAsText()).asJsonObject
                if (request.get("stream")?.asBoolean == true) {
                    onUpdate(AnthropicStreamUpdate(LLMAnthropicAdapter.text(requireNotNull(completed)), "generating"))
                }
            }
        }
        errorBody?.let { return status to it }
        val result = requireNotNull(completed)
        require(result.get("type")?.asString == "message") { "Anthropic upstream returned no message" }
        LLMAnthropicAdapter.webSearchError(result)?.let { return 502 to anthropicError("web_search_error", it) }
        result.getAsJsonObject("usage")?.let { usage ->
            listOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens").forEach { key ->
                totalUsage.addProperty(key, (totalUsage.get(key)?.asInt ?: 0) + (usage.get(key)?.asInt ?: 0))
            }
        }
        val stopReason = result.get("stop_reason")?.asString
        require(!stopReason.isNullOrBlank()) { "Anthropic upstream returned no stop reason" }
        val calls = LLMAnthropicAdapter.functionCalls(result)
        if (calls.isNotEmpty()) {
            if (toolRounds++ >= maxToolRounds) return 502 to anthropicError("tool_round_limit", "工具调用轮数超过限制")
            val outputs = linkedMapOf<String, String>()
            for (call in calls) outputs[call.callId] = executeTool(call)
            LLMAnthropicAdapter.appendContinuation(request, result, outputs)
        } else if (stopReason == "pause_turn") {
            if (++pauses > 8) return 502 to anthropicError("search_round_limit", "Anthropic 搜索续接次数超过限制")
            LLMAnthropicAdapter.appendContinuation(request, result, emptyMap())
            onUpdate(AnthropicStreamUpdate(phase = "web_search"))
        } else {
            require(stopReason != "tool_use") { "Anthropic tool_use response contains no tool calls" }
            result.add("usage", totalUsage)
            return status to LLMAnthropicAdapter.toChatCompletion(result)
        }
    }
}

private fun anthropicError(code: String, message: String): String = JsonObject().apply {
    add("error", JsonObject().apply {
        addProperty("type", code)
        addProperty("code", code)
        addProperty("message", message)
    })
}.toString()

internal suspend fun LLMServices.completeWithAnthropicApi(
    request: LLMServices.NormalizedRequest,
    requester: LLMServices.LLMRequester,
    source: String,
    provider: LLMProvider,
): Pair<Int, String> {
    val body = LLMAnthropicAdapter.fromChatRequest(
        request.body,
        if (toolService.enabled()) toolService.definitions() else com.google.gson.JsonArray(),
        request.reasoningEffort,
        thinkingMode = provider.modelConfig(request.preset).thinkingMode,
    )
    val (status, text) = runAnthropicUpstream(
        client, provider.endpoint(LLMProtocol.ANTHROPIC), provider.apiToken, body, maxToolRounds,
        executeTool = { call -> toolService.execute(call.name, call.arguments, requester.toolContext(request.currentUserText)) },
        onRequest = { outgoing -> logUpstreamRequest(source, outgoing, provider, "anthropic"); debugPrompt(source, outgoing) },
    )
    return status to if (status in 200..299) sanitizeResponseBody(text, request.enableMarkdown) else text
}

internal fun LLMServices.streamFromAnthropic(
    request: LLMServices.NormalizedRequest,
    requester: LLMServices.LLMRequester,
    requestId: Long,
    source: String,
    provider: LLMProvider,
    quotaReservation: LLMQuotaReservation,
): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
    var accepted = false
    var lastPhase: String? = null
    val assistant = StringBuilder()
    suspend fun progress(phase: String, label: String) {
        if (phase == lastPhase) return
        lastPhase = phase
        emit(progressChunk(phase, label))
    }
    suspend fun update(update: AnthropicStreamUpdate) {
        update.phase?.let { phase ->
            progress(phase, when (phase) {
                "thinking" -> "正在整理思路…"
                "web_search" -> "正在进行 Web 搜索…"
                else -> "正在生成回复…"
            })
        }
        if (update.text.isNotEmpty()) {
            assistant.append(update.text)
            emit(responsesTextDeltaChunk(update.text, request.model))
        }
    }
    progress("analyzing", "正在分析问题…")
    try {
        val body = LLMAnthropicAdapter.fromChatRequest(
            request.body,
            if (toolService.enabled()) toolService.definitions() else com.google.gson.JsonArray(),
            request.reasoningEffort,
            stream = true,
            thinkingMode = provider.modelConfig(request.preset).thinkingMode,
        )
        val (status, text) = runAnthropicUpstream(
            client, provider.endpoint(LLMProtocol.ANTHROPIC), provider.apiToken, body, maxToolRounds,
            executeTool = { call ->
                val (phase, label) = toolProgress(call.name)
                progress(phase, label)
                toolService.execute(call.name, call.arguments, requester.toolContext(request.currentUserText))
            },
            onUpdate = { update(it) },
            onAccepted = { accepted = true },
            onRequest = { outgoing -> logUpstreamRequest(source, outgoing, provider, "anthropic"); debugPrompt(source, outgoing) },
        )
        if (status !in 200..299) {
            if (!accepted) dailyQuotaService.refund(quotaReservation)
            updateAccessRecord(requestId, "failed", errorMessage = text.take(512), groupName = requester.groupName, qqUid = requester.uid)
            emit(normalizeUpstreamError(text))
            return@flow
        }
        // Text was already emitted; send only the terminal reason and cumulative usage.
        nonStreamCompletionToStreamChunk(text)?.first?.let { chunk ->
            val terminal = JsonParser.parseString(chunk).asJsonObject
            terminal.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta").remove("content")
            emit(terminal.toString())
        }
        updateAccessRecord(requestId, "completed", parseUsage(text), groupName = requester.groupName, qqUid = requester.uid)
        if (assistant.isNotBlank()) recordConversationAnswer(requester, request.userContent, assistant.toString(), provider)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!accepted) dailyQuotaService.refund(quotaReservation)
        updateAccessRecord(requestId, "failed", errorMessage = e.message, groupName = requester.groupName, qqUid = requester.uid)
        emit(errorJson("upstream_error", e.message ?: "Anthropic 上游请求失败"))
    }
}
