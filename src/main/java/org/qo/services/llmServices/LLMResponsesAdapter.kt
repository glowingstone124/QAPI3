package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object LLMResponsesAdapter {
	fun fromChatRequest(chatBody: String, functionTools: JsonArray, enableWebSearch: Boolean): JsonObject {
		val chat = JsonParser.parseString(chatBody).asJsonObject
		return JsonObject().apply {
			add("model", chat.get("model"))
			add("input", chat.getAsJsonArray("messages").deepCopy())
			addProperty("stream", false)
			chat.get("temperature")?.let { add("temperature", it) }
			chat.get("top_p")?.let { add("top_p", it) }
			chat.get("max_tokens")?.let { add("max_output_tokens", it) }
			(chat.get("user") ?: chat.get("user_id"))
				?.asString
				?.replace(Regex("[^a-zA-Z0-9_-]"), "_")
				?.take(512)
				?.takeIf { it.isNotBlank() }
				?.let { addProperty("user", it) }

			val tools = JsonArray()
			functionTools.forEach { tool ->
				val function = tool.asJsonObject.getAsJsonObject("function") ?: return@forEach
				tools.add(JsonObject().apply {
					addProperty("type", "function")
					add("name", function.get("name"))
					function.get("description")?.let { add("description", it) }
					function.get("parameters")?.let { add("parameters", it) }
				})
			}
			if (enableWebSearch) {
				tools.add(JsonObject().apply { addProperty("type", "web_search") })
			}
			if (tools.size() > 0) {
				add("tools", tools)
				addProperty("tool_choice", "auto")
			}
		}
	}

	fun functionCalls(responseBody: String): List<ResponseFunctionCall> {
		val response = JsonParser.parseString(responseBody).asJsonObject
		return response.getAsJsonArray("output")
			?.mapNotNull { item ->
				val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
				if (obj.get("type")?.asString != "function_call") return@mapNotNull null
				ResponseFunctionCall(
					callId = obj.get("call_id")?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null,
					name = obj.get("name")?.asString ?: return@mapNotNull null,
					arguments = obj.get("arguments")?.asString,
				)
			}
			.orEmpty()
	}

	fun appendToolOutputs(request: JsonObject, responseBody: String, outputs: Map<String, String>) {
		val response = JsonParser.parseString(responseBody).asJsonObject
		val input = request.getAsJsonArray("input")
		response.getAsJsonArray("output")?.forEach { input.add(it.deepCopy()) }
		outputs.forEach { (callId, output) ->
			input.add(JsonObject().apply {
				addProperty("type", "function_call_output")
				addProperty("call_id", callId)
				addProperty("output", output)
			})
		}
	}

	fun toChatCompletion(responseBody: String): String {
		val response = JsonParser.parseString(responseBody).asJsonObject
		val content = extractText(response)
		val usage = response.getAsJsonObject("usage")
		val promptTokens = usage?.get("input_tokens")?.asInt ?: 0
		val completionTokens = usage?.get("output_tokens")?.asInt ?: 0
		return JsonObject().apply {
			addProperty("id", response.get("id")?.asString ?: "resp-deepseek")
			addProperty("object", "chat.completion")
			add("model", response.get("model"))
			add("choices", JsonArray().apply {
				add(JsonObject().apply {
					addProperty("index", 0)
					addProperty("finish_reason", if (response.get("status")?.asString == "incomplete") "length" else "stop")
					add("message", JsonObject().apply {
						addProperty("role", "assistant")
						addProperty("content", content)
					})
				})
			})
			add("usage", JsonObject().apply {
				addProperty("prompt_tokens", promptTokens)
				addProperty("completion_tokens", completionTokens)
				addProperty("total_tokens", promptTokens + completionTokens)
			})
		}.toString()
	}

	private fun extractText(response: JsonObject): String {
		val parts = mutableListOf<String>()
		val sources = linkedMapOf<String, String>()
		response.getAsJsonArray("output")?.forEach { item ->
			val message = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
			if (message.get("type")?.asString != "message") return@forEach
			message.getAsJsonArray("content")?.forEach { block ->
				val text = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
				if (text.get("type")?.asString == "output_text") {
					text.get("text")?.asString?.takeIf { it.isNotBlank() }?.let(parts::add)
					text.getAsJsonArray("annotations")?.forEach { annotation ->
						val citation = annotation.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
						val url = citation.get("url")?.asString?.takeIf { it.isNotBlank() } ?: return@forEach
						val title = citation.get("title")?.asString?.takeIf { it.isNotBlank() } ?: url
						sources.putIfAbsent(url, title)
					}
				}
			}
		}
		if (sources.isNotEmpty()) {
			parts.add("来源：\n" + sources.entries.joinToString("\n") { (url, title) -> "$title $url" })
		}
		return parts.joinToString("\n\n").trim()
	}
}

data class ResponseFunctionCall(val callId: String, val name: String, val arguments: String?)
