package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun LLMServices.summarizeConversation(
	existingSummary: String?,
	messages: JsonArray,
	provider: LLMProvider,
): String? {
	val conversation = messages.mapNotNull { item ->
		val message = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
		val role = message.get("role")?.asString ?: "unknown"
		val content = message.get("content")?.let(::extractTextContent)
			?.takeIf { it.isNotBlank() }
			?: "[非文本内容已省略]"
		"$role: $content"
	}.joinToString("\n")
	if (conversation.isBlank()) return existingSummary

	val instruction = "将多轮对话压缩为后续回答可用的事实摘要。保留用户诉求、已确认的事实、偏好、约束、已完成事项、未解决问题和必要上下文；删除寒暄、重复内容和模型的推理过程。不得添加原文没有的信息。直接输出摘要正文。\n\n${LLMGroupChatPolicy.conversationSummaryRules}"
	val maxOutputTokens = minOf(1200, (provider.summaryContextWindow / 4).coerceAtLeast(1))
	val inputBudget = (
		provider.summaryContextWindow - maxOutputTokens - estimateTextTokens(instruction) - 16
	).coerceAtLeast(1)
	val input = clipTextToTokens(buildString {
		if (!existingSummary.isNullOrBlank()) {
			append("已有摘要：\n").append(existingSummary).append("\n\n")
		}
		append("需要合并的较早对话：\n").append(conversation)
	}, inputBudget)
	val request = JsonObject().apply {
		addProperty("model", provider.summaryModel)
		addProperty("stream", false)
		addProperty("max_tokens", maxOutputTokens)
		add("thinking", JsonObject().apply { addProperty("type", "disabled") })
		add("messages", JsonArray().apply {
			add(JsonObject().apply {
				addProperty("role", "system")
				addProperty("content", instruction)
			})
			add(JsonObject().apply {
				addProperty("role", "user")
				addProperty("content", input)
			})
		})
	}
	return withTimeoutOrNull(groupSummaryTimeoutMs.milliseconds) {
		runCatching {
			val (status, body) = postSummaryUpstream("conversation-compact", request.toString(), provider.summary)
			if (status !in 200..299) return@runCatching null
			parseUsage(body)?.let { logPromptCacheUsage("conversation-compact", it) }
			extractAssistantContent(body)
		}.getOrNull()
	}
}

private fun firstChatChoice(responseBody: String): JsonObject? =
	JsonParser.parseString(responseBody).asJsonObject.getAsJsonArray("choices")
		?.firstOrNull()?.asJsonObject

internal fun LLMServices.extractToolCalls(responseBody: String): List<LLMServices.ToolCall> = runCatching {
	val message = firstChatChoice(responseBody)?.getAsJsonObject("message") ?: return emptyList()
	message.get("tool_calls")?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 }?.asJsonArray?.let { toolCalls ->
		return toolCalls.mapNotNull { item ->
			val call = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			val function = call.getAsJsonObject("function") ?: return@mapNotNull null
			LLMServices.ToolCall(
				id = call.get("id")?.asString ?: "call_${UUID.randomUUID()}",
				name = function.get("name")?.asString ?: return@mapNotNull null,
				arguments = function.get("arguments")?.asString,
			)
		}
	}
	val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
	extractDsmlToolCalls(content)
}.getOrDefault(emptyList())

internal fun LLMServices.extractDsmlToolCalls(content: String): List<LLMServices.ToolCall> {
	if (!content.contains("tool_calls") && !content.contains("invoke name=")) {
		return emptyList()
	}
	val invokeBlocks = Regex("""<[^>\n]*invoke[^>\n]*name=["']([^"']+)["'][^>]*>([\s\S]*?)(?:</[^>]*invoke>|$)""")
		.findAll(content)
		.toList()
	if (invokeBlocks.isNotEmpty()) {
		return invokeBlocks.mapNotNull { invoke ->
			dsmlInvokeToToolCall(invoke.groupValues[1], invoke.groupValues[2])
		}
	}
	val invokeName = Regex("""invoke[^>\n]*name=["']([^"']+)["']""").find(content)?.groupValues?.getOrNull(1)
		?: return emptyList()
	return listOfNotNull(dsmlInvokeToToolCall(invokeName, content))
}

internal fun LLMServices.dsmlInvokeToToolCall(name: String, body: String): LLMServices.ToolCall? {
	val toolName = name.trim()
	if (toolName.isBlank()) {
		return null
	}
	val args = JsonObject()
	Regex("""<[^>\n]*parameter[^>\n]*name=["']([^"']+)["'][^>]*>([\s\S]*?)(?:</[^>]*parameter>|$)""")
		.findAll(body)
		.forEach { parameter ->
			val parameterName = parameter.groupValues[1].trim()
			val value = parameter.groupValues[2].trim()
			if (parameterName.isNotBlank()) {
				args.addProperty(parameterName, value)
			}
		}
	return LLMServices.ToolCall(
		id = "call_${UUID.randomUUID()}",
		name = toolName,
		arguments = args.toString(),
	)
}

internal fun LLMServices.appendAssistantToolCallMessage(
	messages: JsonArray,
	responseBody: String,
	parsedToolCalls: List<LLMServices.ToolCall>
) {
	runCatching {
		val root = JsonParser.parseString(responseBody).asJsonObject
		val choices = root.getAsJsonArray("choices") ?: return
		if (choices.size() == 0) return
		val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return
		messages.add(JsonObject().apply {
			addProperty("role", "assistant")
			if (message.has("content") && !message.get("content").isJsonNull) {
				add("content", message.get("content"))
			} else {
				addProperty("content", "")
			}
			message.getAsJsonArray("tool_calls")?.let {
				add("tool_calls", it.deepCopy())
			} ?: add("tool_calls", JsonArray().apply {
				parsedToolCalls.forEach { call ->
					add(JsonObject().apply {
						addProperty("id", call.id)
						addProperty("type", "function")
						add("function", JsonObject().apply {
							addProperty("name", call.name)
							addProperty("arguments", call.arguments ?: "{}")
						})
					})
				}
			})
		})
	}
}

internal fun LLMServices.extractAssistantContent(responseBody: String): String? = runCatching {
	val root = JsonParser.parseString(responseBody).asJsonObject
	val choices = root.getAsJsonArray("choices") ?: return null
	if (choices.size() == 0) return null
	choices[0].asJsonObject
		.getAsJsonObject("message")
		?.get("content")
		?.asString
		?.trim()
		?.takeIf { it.isNotBlank() }
}.getOrNull()

internal fun LLMServices.parseStreamAssistantContent(body: String): String? = runCatching {
	val root = JsonParser.parseString(body).asJsonObject
	root.getAsJsonArray("choices")
		?.get(0)
		?.asJsonObject
		?.getAsJsonObject("delta")
		?.get("content")
		?.takeIf { !it.isJsonNull }
		?.asString
}.getOrNull()

/**
 * Converts provider metadata into a safe, user-facing phase. Only the phase
 * and a fixed label are sent; tool arguments and hidden reasoning are never
 * forwarded to the browser.
 */
internal fun LLMServices.streamProgress(body: String): Pair<String, String>? = runCatching {
	val root = JsonParser.parseString(body).asJsonObject
	val upstreamType = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase().orEmpty()
	if (upstreamType.contains("web_search")) {
		return@runCatching "web_search" to "正在进行 Web 搜索…"
	}

	val choices = root.getAsJsonArray("choices") ?: return@runCatching null
	if (choices.size() == 0) return@runCatching null
	val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: return@runCatching null
	val toolCalls = delta.get("tool_calls")
		?.takeIf { it.isJsonArray }
		?.asJsonArray
	if (toolCalls != null) {
		for (item in toolCalls) {
			val function = item.takeIf { it.isJsonObject }
				?.asJsonObject
				?.getAsJsonObject("function")
			val name = function?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
			if (!name.isNullOrBlank()) return@runCatching toolProgress(name)
		}
	}

	val reasoning = delta.get("reasoning_content")
		?.takeIf { it.isJsonPrimitive }
		?.asString
	if (!reasoning.isNullOrBlank()) "thinking" to "正在整理思路…" else null
}.getOrNull()

internal fun LLMServices.toolProgress(name: String): Pair<String, String> {
	return when (name.trim().lowercase()) {
		"search_chat_history" -> "query" to "正在查询聊天记录…"
		"search_minecraft_knowledge" -> "query" to "正在查询 Minecraft 资料…"
		"web_fetch" -> "web_search" to "正在读取网页…"
		"search_memory" -> "query" to "正在查询相关记忆…"
		"get_qo_player_profile" -> "query" to "正在查询玩家资料…"
		"get_current_date" -> "query" to "正在查询当前日期…"
		"get_server_status" -> "query" to "正在查询服务器状态…"
		"get_player_rankings" -> "query" to "正在查询排行榜…"
		"query_metro_lines" -> "query" to "正在查询线路信息…"
		else -> {
			val normalized = name.trim().lowercase()
			if (normalized.contains("web") && normalized.contains("search")) {
				"web_search" to "正在进行 Web 搜索…"
			} else if (normalized.contains("search") || normalized.contains("query") || normalized.startsWith("get_")) {
				"query" to "正在调用查询…"
			} else {
				"tool" to "正在调用工具…"
			}
		}
	}
}

internal fun LLMServices.sanitizeResponseBody(responseBody: String, enableMarkdown: Boolean = false): String {
	if (!sanitizeOutput) {
		return responseBody
	}
	return runCatching {
		val root = JsonParser.parseString(responseBody).asJsonObject
		val choices = root.getAsJsonArray("choices") ?: return responseBody
		for (choice in choices) {
			val message = choice.takeIf { it.isJsonObject }
				?.asJsonObject
				?.getAsJsonObject("message")
				?: continue
			val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString ?: continue
			message.addProperty("content", sanitizeAssistantText(content, enableMarkdown))
		}
		root.toString()
	}.getOrDefault(responseBody)
}

internal fun LLMServices.containsToolMarkup(responseBody: String): Boolean = runCatching {
	val choice = firstChatChoice(responseBody) ?: return@runCatching false
	if (choice.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString == "tool_calls") return@runCatching true
	val message = choice.getAsJsonObject("message") ?: return@runCatching false
	val calls = message.get("tool_calls")?.takeUnless { it.isJsonNull }
	if (calls != null && (!calls.isJsonArray || calls.asJsonArray.size() > 0)) return@runCatching true
	val content = message.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
	toolMarkupPattern.containsMatchIn(content)
}.getOrDefault(false)

private val toolMarkupPattern = Regex("""<[^>\n]*(?:tool_calls?|invoke)\b|<[^>\n]*｜｜DSML｜｜""", RegexOption.IGNORE_CASE)

internal fun LLMServices.sanitizeAssistantText(content: String, enableMarkdown: Boolean): String {
	var sanitized = content
		.replace(Regex("""<think>[\s\S]*?</think>"""), "")
		.replace(Regex("""<[^>]*tool_calls[^>]*>[\s\S]*?</[^>]*tool_calls>"""), "")
		.replace(Regex("""<[^>]*invoke\s+name="[^"]+"[^>]*>[\s\S]*?</[^>]*invoke>"""), "")
		.replace(Regex("""</?[^>]*tool_call[^>]*>"""), "")
		.replace(Regex("""</?[^>]*invoke[^>]*>"""), "")
		.replace(Regex("""</?[^>]*DSML[^>]*>"""), "")
	if (!enableMarkdown) {
		sanitized = sanitized
			.replace("```", "")
			.replace("**", "")
			.replace("__", "")
			.replace("`", "")
			.replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1 $2")
	}
	return sanitized.lines()
		.joinToString("\n") { line ->
			if (enableMarkdown) line.trimEnd()
			else line
				.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")
				.replace(Regex("""^\s{0,3}>\s?"""), "")
				.replace(Regex("""^\s{0,3}[-*+]\s+"""), "")
				.trimEnd()
		}
		.let { text -> if (stripEmoji) text.replace(Regex("""[\uD83C-\uDBFF][\uDC00-\uDFFF]"""), "") else text }
		.replace(Regex("""[ʚɞ♡♥★☆♪]+"""), "")
		.replace(Regex("""[（(][^（）()\n]*(?:｡|ω|･|∀|｀|´|＾|＿|▽|д|Д|︿|﹏|╯|╰|；|;)[^（）()\n]*[）)]"""), "")
		.replace(Regex("""\n{3,}"""), "\n\n")
		.trim()
}
