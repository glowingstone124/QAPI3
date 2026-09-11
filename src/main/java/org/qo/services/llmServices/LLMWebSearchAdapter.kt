package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Enables provider-hosted search using the OpenAI Chat Completions wire format. */
internal object LLMWebSearchAdapter {
	fun enableChatCompletions(chatBody: String, functionTools: JsonArray): JsonObject {
		val request = JsonParser.parseString(chatBody).asJsonObject
		request.add("web_search_options", JsonObject())
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
