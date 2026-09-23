package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal fun clientToolOutputTokens(inputTokens: Int, contextWindow: Int): Int {
	val available = contextWindow - inputTokens
	require(available >= 2048) { "建筑工具上下文过大，请开始新对话或缩小选区" }
	return minOf(CLIENT_TOOL_OUTPUT_TOKENS, available)
}

internal fun LLMServices.limitMessagesToContextWindow(messages: JsonArray, contextWindow: Int, request: JsonObject): JsonArray {
	val outputTokens = requestedOutputTokens(request, contextWindow)
	val inputBudget = (contextWindow - outputTokens).coerceAtLeast(1)
	val entries = messages.toList()
	if (estimateTokens(messages) <= inputBudget || entries.isEmpty()) return messages

	val selected = BooleanArray(entries.size)
	var usedTokens = 0
	fun select(index: Int) {
		if (index !in entries.indices || selected[index]) return
		selected[index] = true
		usedTokens += estimateTokens(entries[index])
	}

	select(entries.indexOfFirst { it.isJsonObject && it.asJsonObject.get("role")?.asString == "system" })
	val latestUser = entries.indexOfLast { it.isJsonObject && it.asJsonObject.get("role")?.asString == "user" }
	select(if (latestUser >= 0) latestUser else entries.lastIndex)
	for (index in entries.lastIndex downTo 0) {
		if (selected[index]) continue
		val cost = estimateTokens(entries[index])
		if (usedTokens + cost <= inputBudget) select(index)
	}

	return JsonArray().apply {
		entries.forEachIndexed { index, entry ->
			if (selected[index]) add(entry)
		}
	}
}

internal fun LLMServices.requestedOutputTokens(request: JsonObject, contextWindow: Int): Int {
	val explicit = listOf("max_tokens", "max_completion_tokens", "max_output_tokens").firstNotNullOfOrNull { key ->
		request.get(key)?.let { runCatching { it.asInt }.getOrNull() }
	}
	return (explicit ?: minOf(4096, contextWindow / 4)).coerceAtLeast(0)
}

internal fun LLMServices.estimateTokens(element: JsonElement): Int = when {
	element.isJsonNull -> 0
	element.isJsonPrimitive -> estimateTextTokens(element.asString)
	element.isJsonArray -> element.asJsonArray.sumOf(::estimateTokens)
	element.isJsonObject -> element.asJsonObject.entrySet().sumOf { (key, value) ->
		estimateTextTokens(key) + estimateTokens(value) + 1
	}
	else -> 0
}

internal fun LLMServices.estimateTextTokens(text: String): Int {
	if (text.isBlank()) return 1
	var tokens = 0
	var asciiCharacters = 0
	for (character in text) {
		if (character.code in 0x20..0x7E) {
			asciiCharacters++
		} else {
			tokens += (asciiCharacters + 3) / 4
			asciiCharacters = 0
			tokens++
		}
	}
	tokens += (asciiCharacters + 3) / 4
	return tokens.coerceAtLeast(1)
}

internal fun LLMServices.fitSummaryInput(
	existingSummary: String?,
	messages: List<GroupChatEntry>,
	instruction: String,
	contextWindow: Int,
	outputTokens: Int,
): String {
	val inputBudget = (
		contextWindow - outputTokens - estimateTextTokens(instruction) - 16
	).coerceAtLeast(1)
	var selectedMessages = messages
	while (selectedMessages.size > 1 && estimateTextTokens(summaryInputText(existingSummary, selectedMessages)) > inputBudget) {
		selectedMessages = selectedMessages.drop(1)
	}
	return clipTextToTokens(summaryInputText(existingSummary, selectedMessages), inputBudget)
}

internal fun LLMServices.summaryInputText(existingSummary: String?, messages: List<GroupChatEntry>): String = buildString {
	if (!existingSummary.isNullOrBlank()) {
		append("已有摘要：\n").append(existingSummary).append("\n\n")
	}
	append("需要合并的新消息：\n")
	append(messages.joinToString("\n") { entry ->
		val prefix = if (entry.time > 0) "[${entry.time}] " else ""
		"$prefix${entry.name}(${entry.uid}): ${entry.content}"
	})
}

internal fun LLMServices.clipTextToTokens(text: String, maxTokens: Int): String {
	if (estimateTextTokens(text) <= maxTokens) return text
	var low = 0
	var high = text.length
	while (low < high) {
		val middle = (low + high) / 2
		if (estimateTextTokens(text.substring(middle)) <= maxTokens) {
			high = middle
		} else {
			low = middle + 1
		}
	}
	return text.substring(low)
}
