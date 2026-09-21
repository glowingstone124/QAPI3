package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Enables provider-hosted search using the OpenAI Chat Completions wire format. */
internal object LLMWebSearchAdapter : LLMAdapter {
	override val protocol: LLMProtocol = LLMProtocol.CHAT_COMPLETIONS

	override fun adapt(request: LLMAdapterRequest): JsonObject =
		enableChatCompletions(request.chat.toString(), request.functionTools, request.webSearch)

	fun enableChatCompletions(chatBody: String, functionTools: JsonArray, hostedSearch: Boolean = true): JsonObject {
		val request = JsonParser.parseString(chatBody).asJsonObject
		if (hostedSearch) request.add("web_search_options", JsonObject())
		else request.remove("web_search_options")
		if (functionTools.size() > 0) {
			request.add("tools", JsonArray().apply {
				functionTools.forEach { add(it.deepCopy()) }
			})
			request.addProperty("tool_choice", "auto")
		} else {
			request.remove("tools")
			request.remove("tool_choice")
		}
		return request
	}
}
