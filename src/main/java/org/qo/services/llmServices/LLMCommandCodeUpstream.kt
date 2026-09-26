package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.UUID

internal data class CommandCodeUpdate(val text: String = "", val reasoning: String = "", val phase: String? = null)

/** One /alpha/generate request. Its response is JSONL even for a non-streaming caller. */
internal suspend fun runCommandCodeUpstream(
	client: HttpClient,
	url: String,
	token: String,
	body: JsonObject,
	onUpdate: suspend (CommandCodeUpdate) -> Unit = {},
	onAccepted: () -> Unit = {},
	onRequest: (String) -> Unit = {},
	requestTimeoutMillis: Long? = null,
): Pair<Int, String> {
	val outgoing = body.toString()
	onRequest(outgoing)
	var status = 502
	var result = ""
	client.preparePost(url) {
		if (requestTimeoutMillis != null) timeout {
			this.requestTimeoutMillis = requestTimeoutMillis
			socketTimeoutMillis = requestTimeoutMillis
		}
		header(HttpHeaders.Authorization, "Bearer $token")
		header(HttpHeaders.UserAgent, "cli")
		header("x-command-code-version", "1.54.2")
		header("x-cli-environment", "production")
		contentType(ContentType.Application.Json)
		setBody(outgoing)
	}.execute { response ->
		status = response.status.value
		if (!response.status.isSuccess()) {
			result = response.bodyAsText()
			return@execute
		}
		onAccepted()
		val text = StringBuilder()
		val reasoning = StringBuilder()
		val calls = JsonArray()
		var usage: JsonObject? = null
		var finish: String? = null
		val channel = response.bodyAsChannel()
		while (!channel.isClosedForRead) {
			val line = channel.readUTF8Line() ?: break
			val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
			when (event.get("type")?.asString) {
				"text-delta" -> {
					val delta = event.get("text")?.asString.orEmpty()
					text.append(delta)
					if (delta.isNotEmpty()) onUpdate(CommandCodeUpdate(text = delta, phase = "generating"))
				}

				"reasoning-start" -> onUpdate(CommandCodeUpdate(phase = "thinking"))
				"reasoning-delta" -> {
					val delta = event.get("text")?.asString.orEmpty()
					reasoning.append(delta)
					if (delta.isNotEmpty()) onUpdate(CommandCodeUpdate(reasoning = delta, phase = "thinking"))
				}

				"tool-call" -> {
					val args = listOf("input", "args", "arguments").firstNotNullOfOrNull {
						event.get(it)?.takeIf { value -> value.isJsonObject }
					} ?: JsonObject()
					calls.add(JsonObject().apply {
						addProperty(
							"id",
							event.get("toolCallId")?.asString?.takeIf { it.isNotBlank() }
								?: "call_${UUID.randomUUID()}")
						addProperty("type", "function")
						add("function", JsonObject().apply {
							addProperty("name", event.get("toolName")?.asString ?: "")
							addProperty("arguments", args.toString())
						})
					})
				}

				"finish" -> {
					finish = when (event.get("finishReason")?.asString?.lowercase()) {
						"tool-calls", "tool_calls" -> "tool_calls"
						"length", "max_tokens", "max-tokens", "max_output_tokens" -> "length"
						else -> "stop"
					}
					event.getAsJsonObject("totalUsage")?.let { total ->
						val input = total.get("inputTokens")?.asInt
						val output = total.get("outputTokens")?.asInt
						usage = JsonObject().apply {
							if (input != null) addProperty("prompt_tokens", input)
							if (output != null) addProperty("completion_tokens", output)
							if (input != null && output != null) addProperty("total_tokens", input + output)
							total.getAsJsonObject("inputTokenDetails")?.get("cacheReadTokens")?.asInt?.let {
								addProperty("prompt_cache_hit_tokens", it)
							}
							addProperty("qapi_usage_complete", input != null && output != null)
						}
					}
					break
				}

				"error" -> {
					val error = event.get("error")
					val message = if (error?.isJsonObject == true) error.asJsonObject.get("message")?.asString
					else error?.takeUnless { it.isJsonNull }?.asString
					result =
						commandCodeError(message ?: event.get("message")?.asString ?: "Command Code upstream error")
					status = 502
					return@execute
				}
			}
		}
		if (finish == null) {
			status = 502
			result = commandCodeError("Command Code stream ended without finish")
			return@execute
		}
		println("[LLM] CommandCode completed finish=$finish output_tokens=${usage?.get("completion_tokens")} reasoning_chars=${reasoning.length} text_chars=${text.length} tool_calls=${calls.size()}")
		result = JsonObject().apply {
			addProperty("id", "chatcmpl-${UUID.randomUUID()}")
			addProperty("object", "chat.completion")
			addProperty("model", body.getAsJsonObject("params").get("model").asString)
			add("choices", JsonArray().apply {
				add(JsonObject().apply {
					addProperty("index", 0)
					add("message", JsonObject().apply {
						addProperty("role", "assistant")
						addProperty("content", text.toString())
						if (reasoning.isNotEmpty()) addProperty("reasoning_content", reasoning.toString())
						if (calls.size() > 0) add("tool_calls", calls)
					})
					addProperty("finish_reason", finish)
				})
			})
			add("usage", usage ?: JsonObject().apply { addProperty("qapi_usage_complete", false) })
		}.toString()
	}
	return status to result
}

private fun commandCodeError(message: String) = JsonObject().apply {
	add("error", JsonObject().apply {
		addProperty("type", "upstream_error")
		addProperty("message", message)
	})
}.toString()

internal data class LLMCompletionOutcome(
	val status: Int,
	val body: String,
	val request: LLMServices.NormalizedRequest,
	val provider: LLMProvider,
)

internal data class CommandCodeAttempt(val status: Int, val body: String, val allowFallback: Boolean = false)

private class CommandCodeConnectionFailure(cause: Exception) : RuntimeException(cause)

private val COMMANDCODE_EXCLUDED_TOOLS = setOf("get_remain_balance")

internal fun shouldFallbackCommandCode(status: Int, body: String, round: Int, textEmitted: Boolean): Boolean =
	round == 0 && !textEmitted && status in 500..599 &&
			!body.contains("tool_round_limit")

internal fun LLMServices.commandCodeFallbackRequest(
	request: LLMServices.NormalizedRequest,
	fallback: LLMProvider
): LLMServices.NormalizedRequest {
	val selected = fallback.forMode(request.preset)
	val model = selected.modelName(request.preset)
		?: throw IllegalArgumentException("Fallback provider has no '${request.preset}' model")
	val body = JsonParser.parseString(request.body).asJsonObject.apply {
		addProperty("model", model)
		if (selected.protocol(request.preset) == LLMProtocol.CHAT_COMPLETIONS) {
			addProperty("reasoning_effort", request.reasoningEffort.wireValue)
			if (selected.name.contains("deepseek", ignoreCase = true)) add("thinking", JsonObject().apply {
				addProperty("type", if (request.reasoningEffort == LLMReasoningEffort.NONE) "disabled" else "enabled")
			})
		}
		add("messages", limitMessagesToContextWindow(getAsJsonArray("messages"), selected.contextWindow, this))
	}
	return request.copy(
		model = model,
		body = body.toString(),
		pricing = selected.modelConfig(request.preset).pricing?.at(java.time.Instant.now())
	)
}

internal suspend fun LLMServices.completeWithFallback(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	source: String,
	provider: LLMProvider,
): LLMCompletionOutcome {
	val fallback = provider.fallback
	if (request.clientTools != null || fallback == null || provider.protocol(request.preset) != LLMProtocol.COMMANDCODE) {
		val (status, body) = completeWithOptionalTools(request, requester, source, provider)
		return LLMCompletionOutcome(status, body, request, provider)
	}
	val primary = try {
		completeWithCommandCodeApi(request, requester, source, provider)
	} catch (error: CommandCodeConnectionFailure) {
		LLMErrorLog.record("$source/commandcode-primary", error, provider.name, requester)
		println("[LLM] Command Code connection failed; trying ${fallback.name}: ${error.message}")
		null
	}
	if (primary != null && !primary.allowFallback) {
		return LLMCompletionOutcome(primary.status, primary.body, request, provider)
	}
	val fallbackProvider = fallback.forMode(request.preset)
	val fallbackRequest = commandCodeFallbackRequest(request, fallback)
	val (status, body) = try {
		completeWithOptionalTools(fallbackRequest, requester, "$source/fallback", fallbackProvider)
	} catch (error: Exception) {
		if (error is kotlinx.coroutines.CancellationException) throw error
		LLMErrorLog.record("$source/fallback", error, fallbackProvider.name, requester)
		throw error
	}
	return LLMCompletionOutcome(status, body, fallbackRequest, fallbackProvider)
}

internal suspend fun LLMServices.completeWithCommandCodeApi(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	source: String,
	provider: LLMProvider,
): CommandCodeAttempt {
	val chat = JsonParser.parseString(request.body).asJsonObject
	val tools = toolService.definitions(COMMANDCODE_EXCLUDED_TOOLS)
	var usage: LLMServices.Usage? = null
	repeat(maxToolRounds) { round ->
		val body = LLMAdapterRegistry.forProtocol(LLMProtocol.COMMANDCODE).adapt(
			LLMAdapterRequest(chat, tools, request.reasoningEffort)
		)
		val (status, response) = try {
			runCommandCodeUpstream(
				client, provider.commandCodeUrl, provider.apiToken, body,
				onRequest = {
					logUpstreamRequest(
						"$source/commandcode-round-${round + 1}",
						it,
						provider,
						"commandcode"
					); debugPrompt(source, it)
				})
		} catch (error: Exception) {
			if (error is kotlinx.coroutines.CancellationException) throw error
			if (round > 0) throw IllegalStateException("Command Code connection failed after tool execution", error)
			throw CommandCodeConnectionFailure(error)
		}
		if (status !in 200..299) return CommandCodeAttempt(
			status, withUsage(response, usage), shouldFallbackCommandCode(status, response, round, false),
		)
		usage = accumulateUsage(usage, parseUsage(response))
		val calls = extractToolCalls(response)
		if (calls.isEmpty()) return CommandCodeAttempt(
			status,
			sanitizeResponseBody(withUsage(response, usage), request.enableMarkdown)
		)
		appendAssistantToolCallMessage(chat.getAsJsonArray("messages"), response, calls)
		val assistant = chat.getAsJsonArray("messages").last().asJsonObject
		JsonParser.parseString(response).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
			.getAsJsonObject("message").get("reasoning_content")
			?.let { assistant.add("reasoning_content", it.deepCopy()) }
		calls.forEach { call ->
			chat.getAsJsonArray("messages").add(JsonObject().apply {
				addProperty("role", "tool")
				addProperty("tool_call_id", call.id)
				addProperty(
					"content",
					toolService.execute(
						call.name,
						call.arguments,
						requester.toolContext(request.currentUserText),
						COMMANDCODE_EXCLUDED_TOOLS
					)
				)
			})
		}
	}
	return CommandCodeAttempt(502, withUsage(commandCodeError("Command Code tool round limit exceeded"), usage))
}

internal fun LLMServices.streamFromCommandCode(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	requestId: Long,
	source: String,
	provider: LLMProvider,
	quotaReservation: LLMQuotaReservation,
): Flow<String> = flow {
	val chat = JsonParser.parseString(request.body).asJsonObject
	val tools = toolService.definitions(COMMANDCODE_EXCLUDED_TOOLS)
	var usage: LLMServices.Usage? = null
	val assistantText = StringBuilder()
	var phase: String? = null
	suspend fun progress(value: String) {
		if (phase == value) return
		phase = value
		emit(progressChunk(value, if (value == "thinking") "正在整理思路…" else "正在生成回复…"))
	}
	emit(progressChunk("analyzing", "正在分析问题…"))
	try {
		repeat(maxToolRounds) { round ->
			val body = LLMAdapterRegistry.forProtocol(LLMProtocol.COMMANDCODE).adapt(
				LLMAdapterRequest(chat, tools, request.reasoningEffort, stream = true)
			)
			val (status, response) = try {
				runCommandCodeUpstream(
					client, provider.commandCodeUrl, provider.apiToken, body,
					onUpdate = { update ->
						update.phase?.let { progress(it) }
						if (update.text.isNotEmpty()) {
							assistantText.append(update.text)
							emit(responsesTextDeltaChunk(update.text, request.model))
						}
					},
					onRequest = {
						logUpstreamRequest(
							"$source/commandcode-round-${round + 1}",
							it,
							provider,
							"commandcode"
						); debugPrompt(source, it)
					})
			} catch (error: Exception) {
				if (error is kotlinx.coroutines.CancellationException) throw error
				LLMErrorLog.record("$source/commandcode-round-${round + 1}", error, provider.name, requester, requestId)
				if (round == 0 && assistantText.isEmpty() && provider.fallback != null) {
					val fallback = provider.fallback.forMode(request.preset)
					val fallbackRequest = commandCodeFallbackRequest(request, provider.fallback)
					emitAll(
						streamCommandCodeFallback(
							fallbackRequest,
							requester,
							requestId,
							"$source/fallback",
							fallback,
							quotaReservation
						)
					)
					return@flow
				}
				throw error
			}
			if (status !in 200..299) {
				if (shouldFallbackCommandCode(
						status,
						response,
						round,
						assistantText.isNotEmpty()
					) && provider.fallback != null
				) {
					val fallback = provider.fallback.forMode(request.preset)
					val fallbackRequest = commandCodeFallbackRequest(request, provider.fallback)
					emitAll(
						streamCommandCodeFallback(
							fallbackRequest,
							requester,
							requestId,
							"$source/fallback",
							fallback,
							quotaReservation
						)
					)
					return@flow
				}
				refundUsage(quotaReservation, usage, request, provider, requester.conversationId)
				updateAccessRecord(
					requestId,
					"failed",
					errorMessage = response.take(512),
					groupName = requester.groupName,
					qqUid = requester.uid
				)
				emit(normalizeUpstreamError(response))
				return@flow
			}
			usage = accumulateUsage(usage, parseUsage(response))
			val calls = extractToolCalls(response)
			if (calls.isEmpty()) {
				val settled = settleUsage(quotaReservation, usage, request, provider, requester.conversationId)
				emit(attachQuota("{}", settled))
				updateAccessRecord(
					requestId,
					"completed",
					usage,
					groupName = requester.groupName,
					qqUid = requester.uid
				)
				if (assistantText.isNotBlank()) recordConversationAnswer(
					requester,
					request.userContent,
					assistantText.toString(),
					provider
				)
				return@flow
			}
			appendAssistantToolCallMessage(chat.getAsJsonArray("messages"), response, calls)
			val assistant = chat.getAsJsonArray("messages").last().asJsonObject
			JsonParser.parseString(response).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
				.getAsJsonObject("message").get("reasoning_content")
				?.let { assistant.add("reasoning_content", it.deepCopy()) }
			for (call in calls) {
				val (toolPhase, label) = toolProgress(call.name)
				emit(progressChunk(toolPhase, label))
				chat.getAsJsonArray("messages").add(JsonObject().apply {
					addProperty("role", "tool")
					addProperty("tool_call_id", call.id)
					addProperty(
						"content",
						toolService.execute(
							call.name,
							call.arguments,
							requester.toolContext(request.currentUserText),
							COMMANDCODE_EXCLUDED_TOOLS
						)
					)
				})
			}
		}
		refundUsage(quotaReservation, usage, request, provider, requester.conversationId)
		updateAccessRecord(
			requestId,
			"failed",
			errorMessage = "Command Code tool round limit exceeded",
			groupName = requester.groupName,
			qqUid = requester.uid
		)
		emit(commandCodeError("Command Code tool round limit exceeded"))
	} catch (error: kotlinx.coroutines.CancellationException) {
		throw error
	} catch (error: Exception) {
		LLMErrorLog.record("$source/commandcode-stream", error, provider.name, requester, requestId)
		if (error !is QuotaSettlementException) refundUsage(
			quotaReservation,
			usage,
			request,
			provider,
			requester.conversationId
		)
		updateAccessRecord(
			requestId,
			"failed",
			errorMessage = error.message,
			groupName = requester.groupName,
			qqUid = requester.uid
		)
		emit(commandCodeError(error.message ?: "Command Code request failed"))
	}
}

private fun LLMServices.streamCommandCodeFallback(
	request: LLMServices.NormalizedRequest,
	requester: LLMServices.LLMRequester,
	requestId: Long,
	source: String,
	provider: LLMProvider,
	reservation: LLMQuotaReservation,
): Flow<String> = when (provider.protocol(request.preset)) {
	LLMProtocol.CHAT_COMPLETIONS -> streamFromUpstream(request, requester, requestId, source, provider, reservation)
	LLMProtocol.RESPONSES -> streamFromResponses(request, requester, requestId, source, provider, reservation)
	LLMProtocol.ANTHROPIC -> streamFromAnthropic(request, requester, requestId, source, provider, reservation)
	LLMProtocol.COMMANDCODE -> streamFromCommandCode(request, requester, requestId, source, provider, reservation)
}
