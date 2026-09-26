package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow

internal const val CLIENT_TOOL_OUTPUT_TOKENS = 4_096
private const val CLIENT_TOOL_DEFINITION_MAX_COUNT = 64
private const val COMMANDCODE_CLIENT_TOOL_TIMEOUT_MILLIS = 300_000L

private fun prependClientInstruction(chat: JsonObject, instruction: String) {
	val previous = chat.getAsJsonArray("messages")
	chat.add("messages", JsonArray().apply {
		add(JsonObject().apply {
			addProperty("role", "system")
			addProperty("content", instruction)
		})
		previous.forEach(::add)
	})
}

internal fun clientToolStepChat(chat: JsonObject): JsonObject = chat.deepCopy().apply {
	if (get("tool_choice")?.asString == "none") return@apply
	prependClientInstruction(
		this,
        """
        Kotshi Builder 会自动续接工具结果。同一阶段中彼此独立的编辑和预览可合并成批；有依赖、需要最新 revision 或结果时再分批。优先区域和构件工具，不展开逐格坐标或重述完整几何。
        遵守当前 planMode：fast 直接施工，不要求创建或确认 Plan；plan 保存具体设计及阶段并等待用户界面确认，不能自行授权；build 按已确认的设计推进，设计变更需重新确认。
        先区分新建、局部修改、续建或讨论。模糊需求应说明少量合理假设并推进；用途不明或可能破坏现有结构时问关键问题。设计应覆盖占地、高度、朝向、布局、材料和验收条件；保留用户指定的风格和未要求修改的区域。平顶、单材质、实心或悬浮结构是否合适取决于用途，不强制套用小屋、坡顶、石基座或装饰模板。
        首批形成可辨认的主体，再分阶段完善。依据当前几何、区域查询和真实预览修正问题；启发式 quality 提示不是审美评分或完工认证。预留最终预览预算；收到客户端最终复核图片后，检查最后修改是否满足需求，需要修复则继续工具。
        只报告已执行且有证据的结果，区分占用检查、图片检查和未验证部分。预算不足、截图失败或效果未满足要求时明确待办，不能把工具执行成功等同于设计完成。
        """.trimIndent()
	)
}

/** Tool feedback is not a new user task when recording the final conversation. */
internal fun clientOriginalUserMessage(messages: JsonArray): JsonObject? {
	val beforeTools = messages.toList().takeWhile { !it.asJsonObject.has("tool_calls") }
	return beforeTools.lastOrNull { it.asJsonObject.get("role")?.asString == "user" }?.asJsonObject
}

/** Opt-in Web-only relay. These tools must never reach LLMToolService.execute. */
internal fun extractClientTools(request: JsonObject, source: String?): JsonArray? {
	val execution = request.remove("tool_execution")?.asString
	if (execution == null) return null
	require(execution == "client" && source == "web") { "客户端工具仅用于 Web Builder" }
	val tools = request.getAsJsonArray("tools") ?: error("客户端工具缺少 tools 定义")
	require(tools.size() in 1..CLIENT_TOOL_DEFINITION_MAX_COUNT && tools.toString().length <= 32768) { "客户端工具定义过多或过大" }
	val names = mutableSetOf<String>()
	tools.forEach { item ->
		val tool = item.asJsonObject
		require(tool.get("type")?.asString == "function") { "仅支持客户端 function 工具" }
		val function = tool.getAsJsonObject("function") ?: error("缺少 function 定义")
		val name = function.get("name")?.asString?.trim()
		require(!name.isNullOrBlank() && name.length <= 128 && names.add(name)) { "客户端工具名称无效或重复" }
		require(
			function.getAsJsonObject("parameters")?.get("type")?.asString == "object"
		) { "工具参数必须为 object schema" }
	}
	require(
		request.get("tool_choice")?.asString in setOf(
			null,
			"auto",
			"none"
		)
	) { "客户端 tool_choice 只支持 auto 或 none" }
	validateClientToolHistory(request.getAsJsonArray("messages") ?: error("缺少 messages"))
	return tools.deepCopy()
}

internal fun validateClientToolHistory(messages: JsonArray) {
	val seen = mutableSetOf<String>()
	val pending = mutableSetOf<String>()
	messages.forEach { item ->
		val message = item.asJsonObject
		val role = message.get("role")?.asString
		if (role == "tool") {
			require(pending.remove(message.get("tool_call_id")?.asString)) { "工具结果缺少对应调用或重复回传" }
			require(message.get("content")?.isJsonPrimitive == true) { "工具结果必须为字符串；图片另附 user 消息" }
		} else {
			require(pending.isEmpty()) { "工具调用结果不完整" }
			message.getAsJsonArray("tool_calls")?.let { calls ->
				require(role == "assistant" && calls.size() in 1..256) { "无效的 assistant 工具调用" }
				calls.forEach { callItem ->
					val call = callItem.asJsonObject
					val id = call.get("id")?.asString.orEmpty()
					require(id.isNotBlank() && id.length <= 256 && seen.add(id)) { "工具调用 ID 无效或重复" }
					val name = call.getAsJsonObject("function")?.get("name")?.asString?.trim()
					require(
						call.get("type")?.asString == "function" &&
							!name.isNullOrBlank() && name.length <= 128
					) { "客户端工具调用格式无效" }
					pending.add(id)
				}
			}
		}
	}
	require(pending.isEmpty()) { "工具调用结果不完整" }
}

// Data URLs are images, not hundreds of thousands of text tokens.
internal fun clientContextForEstimate(value: JsonElement): JsonElement = when {
	value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(clientContextForEstimate(it)) } }
	value.isJsonObject -> if (value.asJsonObject.get("type")?.asString == "image_url") {
		com.google.gson.JsonPrimitive("i".repeat(16384))
	} else JsonObject().apply {
		value.asJsonObject.entrySet().forEach { (key, child) -> add(key, clientContextForEstimate(child)) }
	}

	else -> value.deepCopy()
}

private fun clientMessageText(value: JsonElement?): String = when {
	value == null || value.isJsonNull -> ""
	value.isJsonPrimitive -> value.asString
	value.isJsonArray -> value.asJsonArray.mapNotNull { part ->
		part.takeIf { it.isJsonObject && it.asJsonObject.get("type")?.asString in setOf("text", "input_text") }
			?.asJsonObject?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
	}.joinToString("\n")

	else -> ""
}

/** Keep the live structure and current tool chain; spend spare context on recent text turns. */
internal fun LLMServices.compactClientToolMessages(
	messages: JsonArray,
	tools: JsonArray,
	contextWindow: Int
): JsonArray {
	val entries = messages.map { it.asJsonObject }
	val contextIndex = entries.indexOfLast { message ->
		message.get("role")?.asString == "user" &&
				clientMessageText(message.get("content")).contains("[Kotshi Builder 工作区上下文]")
	}
	if (contextIndex < 0) return messages

	val stable = entries.take(contextIndex).filter { it.get("role")?.asString in setOf("system", "developer") }
	val current = entries.drop(contextIndex)
	val essential = JsonArray().apply { stable.forEach(::add); current.forEach(::add) }
	val essentialTokens = estimateTokens(clientContextForEstimate(essential)) + estimateTokens(tools)
	val historyBudget = (contextWindow - CLIENT_TOOL_OUTPUT_TOKENS - essentialTokens).coerceIn(0, 8192)

	val turns = mutableListOf<MutableList<JsonObject>>()
	for (message in entries.take(contextIndex)) {
		val role = message.get("role")?.asString
		if (role !in setOf("user", "assistant") || message.has("tool_calls")) continue
		val content = clientMessageText(message.get("content")).trim()
		if (content.isEmpty() || content.startsWith("[generate_preview_image 返回结果]") ||
			content.startsWith("[Builder 执行进度]")
		) continue
		if (role == "user") turns.add(mutableListOf())
		if (turns.isEmpty()) continue
		turns.last().add(JsonObject().apply {
			addProperty("role", role)
			addProperty("content", content)
		})
	}
	val retained = mutableListOf<List<JsonObject>>()
	var used = 0
	for (turn in turns.asReversed()) {
		val cost = estimateTokens(JsonArray().apply { turn.forEach(::add) })
		if (used + cost > historyBudget) break
		retained.add(turn)
		used += cost
	}
	return JsonArray().apply {
		stable.forEach(::add)
		retained.asReversed().forEach { turn -> turn.forEach(::add) }
		current.forEach(::add)
	}
}

internal fun nativeToolCalls(calls: List<ResponseFunctionCall>) = JsonArray().apply {
	calls.forEach { call ->
		add(JsonObject().apply {
			addProperty("id", call.callId); addProperty("type", "function")
			add(
				"function",
				JsonObject().apply { addProperty("name", call.name); addProperty("arguments", call.arguments ?: "{}") })
		})
	}
}

private fun truncatedClientCompletion(root: JsonObject): String = JsonObject().apply {
	root.get("id")?.let { add("id", it.deepCopy()) }
	root.get("model")?.let { add("model", it.deepCopy()) }
	add("choices", JsonArray().apply {
		add(JsonObject().apply {
			addProperty("finish_reason", "length")
			add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "") })
		})
	})
	root.get("usage")?.let { add("usage", it.deepCopy()) }
}.toString()

/** Exactly one model turn, with no local executor and no server-side tool loop. */
internal suspend fun runClientToolUpstream(
	client: HttpClient, url: String, token: String, protocol: LLMProtocol,
	chat: JsonObject, tools: JsonArray, effort: LLMReasoningEffort,
	onRequest: (String) -> Unit = {},
	onCommandCodeUpdate: suspend (CommandCodeUpdate) -> Unit = {},
): Pair<Int, String> {
	val allowTools = chat.get("tool_choice")?.asString != "none"
	val definitions = if (allowTools) tools else JsonArray()
	val outgoing = LLMAdapterRegistry.forProtocol(protocol).adapt(
		LLMAdapterRequest(
			chat = chat,
			functionTools = if (protocol == LLMProtocol.COMMANDCODE) definitions else tools,
			reasoningEffort = if (protocol == LLMProtocol.ANTHROPIC) LLMReasoningEffort.NONE else effort,
			webSearch = false,
		)
	).apply {
		when (protocol) {
			LLMProtocol.RESPONSES -> if (!allowTools) addProperty("tool_choice", "none")
			else addProperty("parallel_tool_calls", true)

			// Stateless browser continuations cannot echo native signed thinking blocks.
			LLMProtocol.ANTHROPIC -> if (!allowTools) {
				add("tool_choice", JsonObject().apply { addProperty("type", "none") })
			} else {
				getAsJsonObject("tool_choice")?.addProperty("disable_parallel_tool_use", false)
			}

			LLMProtocol.CHAT_COMPLETIONS -> {
				addProperty("stream", false)
				remove("stream_options")
				add("tools", tools)
				addProperty("tool_choice", if (allowTools) "auto" else "none")
			}

			LLMProtocol.COMMANDCODE -> Unit
		}
	}
	if (protocol == LLMProtocol.COMMANDCODE) return runCommandCodeUpstream(
		client,
		url,
		token,
		outgoing,
		onUpdate = onCommandCodeUpdate,
		onRequest = onRequest,
		requestTimeoutMillis = COMMANDCODE_CLIENT_TOOL_TIMEOUT_MILLIS
	)
	onRequest(outgoing.toString())
	val response = client.post(url) {
		if (protocol == LLMProtocol.ANTHROPIC) {
			header("x-api-key", token); header("anthropic-version", "2023-06-01")
		} else header(HttpHeaders.Authorization, "Bearer $token")
		contentType(ContentType.Application.Json); setBody(outgoing.toString())
	}
	val text = response.bodyAsText()
	if (response.status.value !in 200..299) return response.status.value to text
	val converted = when (protocol) {
		LLMProtocol.RESPONSES -> {
			val root = JsonParser.parseString(text).asJsonObject
			require((root.get("error") == null || root.get("error").isJsonNull) && root.get("status")?.asString != "failed") { "Responses 请求失败" }
			if (root.get("status")?.asString == "incomplete") truncatedClientCompletion(root)
			else LLMResponsesAdapter.toChatCompletion(text)
		}

		LLMProtocol.ANTHROPIC -> {
			val root = JsonParser.parseString(text).asJsonObject
			require(
				root.get("type")?.asString == "message" && root.get("stop_reason")?.asString in setOf(
					"end_turn",
					"tool_use",
					"stop_sequence",
					"max_tokens"
				)
			) { "Anthropic 请求未完整完成" }
			if (root.get("stop_reason")?.asString == "max_tokens") truncatedClientCompletion(root)
			else LLMAnthropicAdapter.toChatCompletion(root)
		}

		else -> text
	}
	return response.status.value to converted
}

private fun clientToolFinishReason(body: String): String? = runCatching {
	JsonParser.parseString(body).asJsonObject.getAsJsonArray("choices")
		?.firstOrNull()?.asJsonObject?.get("finish_reason")?.asString
}.getOrNull()

internal fun LLMServices.clientToolRetryChat(
	chat: JsonObject, tools: JsonArray, contextWindow: Int,
): JsonObject? {
	val retry = chat.deepCopy()
	prependClientInstruction(
		retry,
		"上一尝试达到长度上限，该尝试没有执行工具。输出预算不会增加。把当前任务拆成下一批最小且彼此独立的工具调用；可以合并多个无依赖编辑和预览，但参数只写工具需要的字段，然后立即结束本次回复。浏览器执行后会再次调用你继续剩余工作。不要重述几何、展开全部坐标或输出完整施工计划；任务已完成时只写简短总结。"
	)
	val inputTokens = estimateTokens(clientContextForEstimate(retry.getAsJsonArray("messages"))) + estimateTokens(tools)
	val retryLimit = minOf(
		CLIENT_TOOL_OUTPUT_TOKENS,
		chat.get("max_tokens")?.asInt ?: CLIENT_TOOL_OUTPUT_TOKENS,
		contextWindow - inputTokens
	)
	if (retryLimit < 512) return null
	retry.addProperty("max_tokens", retryLimit)
	return retry
}

/** Retry a truncated model step once from the same unmodified browser state. */
internal suspend fun LLMServices.runClientToolAgentTurn(
	client: HttpClient, url: String, token: String, protocol: LLMProtocol,
	chat: JsonObject, tools: JsonArray, effort: LLMReasoningEffort, contextWindow: Int,
	onRequest: (String) -> Unit = {},
	onCommandCodeUpdate: suspend (CommandCodeUpdate) -> Unit = {},
): Pair<Int, String> {
	val step = chat.deepCopy()
	val stepEffort = configureClientToolStep(step, protocol, effort)
	val first =
		runClientToolUpstream(client, url, token, protocol, step, tools, stepEffort, onRequest, onCommandCodeUpdate)
	if (first.first !in 200..299 || clientToolFinishReason(first.second) != "length") return first
	val firstUsage = parseUsage(first.second)
	println("[LLM] Builder step truncated protocol=${protocol.wireValue} requested_output=${step.get("max_tokens")?.asInt} output_tokens=${firstUsage?.completionTokens} reasoning_tokens=${firstUsage?.reasoningTokens}")
	val retryChat = clientToolRetryChat(step, tools, contextWindow)
		?: return 422 to withUsage(errorJson("client_tool_length", clientToolFinishError("length")), firstUsage)
	if (protocol == LLMProtocol.COMMANDCODE) {
		// The CLI continues unfinished thinking instead of restarting the same task.
		// Keep this bounded recovery state on the server; never replay partial tool calls.
		val reasoning = runCatching {
			JsonParser.parseString(first.second).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
				.getAsJsonObject("message").get("reasoning_content")?.asString
		}.getOrNull()
		if (!reasoning.isNullOrBlank()) {
			val messages = retryChat.getAsJsonArray("messages")
			val continuation = JsonObject().apply {
				addProperty("role", "assistant")
				addProperty("content", "")
				addProperty("reasoning_content", reasoning.takeLast(16_384))
			}
			val instruction = JsonObject().apply {
				addProperty("role", "user")
				addProperty(
					"content",
					"继续上述未完成的步骤。上一尝试没有执行任何工具；现在只提交下一项最小工具调用，随后等待浏览器结果。"
				)
			}
			val recoveryTokens = estimateTokens(continuation) + estimateTokens(instruction)
			val inputTokens = estimateTokens(clientContextForEstimate(messages)) + estimateTokens(tools)
			if (inputTokens + recoveryTokens + retryChat.get("max_tokens").asInt <= contextWindow) {
				messages.add(continuation)
				messages.add(instruction)
			}
		}
	}
	val second = runClientToolUpstream(
		client,
		url,
		token,
		protocol,
		retryChat,
		tools,
		if (stepEffort == LLMReasoningEffort.NONE) stepEffort else LLMReasoningEffort.LOW,
		onRequest,
		onCommandCodeUpdate
	)
	val usage =
		if (firstUsage == null) parseUsage(second.second) else accumulateUsage(firstUsage, parseUsage(second.second))
	if (second.first !in 200..299) return second.first to withUsage(second.second, usage)
	if (clientToolFinishReason(second.second) == "length") {
		println("[LLM] Builder retry truncated protocol=${protocol.wireValue} requested_output=${retryChat.get("max_tokens")?.asInt} total_output_tokens=${usage?.completionTokens} reasoning_tokens=${usage?.reasoningTokens}")
		return 422 to withUsage(
			errorJson(
				"client_tool_length",
				"模型连续两次达到建筑操作长度上限，本批工具未执行；请继续分步操作"
			), usage
		)
	}
	return second.first to withUsage(second.second, usage)
}

/** Preserve a complete native batch so the browser can apply independent calls together. */
internal fun limitClientToolStep(root: JsonObject): JsonObject {
	return root
}

internal fun configureClientToolStep(
	chat: JsonObject,
	protocol: LLMProtocol,
	requestedEffort: LLMReasoningEffort
): LLMReasoningEffort {
	chat.remove("max_completion_tokens")
	chat.remove("max_output_tokens")
	chat.addProperty(
		"max_tokens",
		minOf(chat.get("max_tokens")?.asInt ?: CLIENT_TOOL_OUTPUT_TOKENS, CLIENT_TOOL_OUTPUT_TOKENS)
	)
	if (chat.get("tool_choice")?.asString == "none") return requestedEffort
	if (protocol == LLMProtocol.COMMANDCODE) return LLMReasoningEffort.LOW
	return LLMReasoningEffort.NONE
}
