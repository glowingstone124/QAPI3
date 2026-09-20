package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** OpenAI chat messages to Command Code's /alpha/generate wire. */
internal object LLMCommandCodeAdapter {
	fun fromChatRequest(chat: JsonObject, tools: JsonArray, reasoningEffort: LLMReasoningEffort): JsonObject {
		val messages = JsonArray()
		val system = mutableListOf<String>()
		val knownCalls = mutableMapOf<String, String>()
		chat.getAsJsonArray("messages").forEach { message ->
			val obj = message.asJsonObject
			obj.getAsJsonArray("tool_calls")?.forEach { call ->
				val tool = call.asJsonObject
				knownCalls[tool.get("id").asString] = tool.getAsJsonObject("function").get("name").asString
			}
		}
		val aliases = mutableMapOf<String, String>()
		fun wireId(id: String): String = if (id.length <= 64) id else aliases.getOrPut(id) { "cc-${aliases.size + 1}" }

		chat.getAsJsonArray("messages").forEach { message ->
			val obj = message.asJsonObject
			val role = obj.get("role")?.asString.orEmpty()
			val content = obj.get("content")
			when (role) {
				"system", "developer" -> textContent(content)?.takeIf { it.isNotBlank() }?.let(system::add)
				"user" -> contentParts(content).takeIf { it.size() > 0 }?.let { parts ->
					messages.add(wireMessage("user", parts))
				}

				"assistant" -> {
					val parts = JsonArray()
					obj.get("reasoning_content")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
						?.let {
							parts.add(JsonObject().apply { addProperty("type", "reasoning"); addProperty("text", it) })
						}
					contentParts(content).forEach(parts::add)
					obj.getAsJsonArray("tool_calls")?.forEach { call ->
						val tool = call.asJsonObject
						val function = tool.getAsJsonObject("function")
						val input = runCatching {
							JsonParser.parseString(
								function.get("arguments")?.asString ?: "{}"
							).asJsonObject
						}
							.getOrDefault(JsonObject())
						parts.add(JsonObject().apply {
							addProperty("type", "tool-call")
							addProperty("toolCallId", wireId(tool.get("id").asString))
							addProperty("toolName", function.get("name").asString)
							add("input", input)
						})
					}
					if (parts.size() > 0) messages.add(wireMessage("assistant", parts))
				}

				"tool" -> {
					val id = obj.get("tool_call_id")?.asString.orEmpty()
					val name = knownCalls[id] ?: return@forEach
					messages.add(wireMessage("tool", JsonArray().apply {
						add(JsonObject().apply {
							addProperty("type", "tool-result")
							addProperty("toolCallId", wireId(id))
							addProperty("toolName", name)
							add("output", JsonObject().apply {
								addProperty("type", "text")
								addProperty(
									"value",
									textContent(content)?.ifBlank { "(empty tool result)" } ?: "(empty tool result)")
							})
						})
					}))
				}
			}
		}
		val wireTools = JsonArray()
		tools.forEach { tool ->
			val function = tool.asJsonObject.getAsJsonObject("function") ?: return@forEach
			val name = function.get("name")?.asString ?: return@forEach
			wireTools.add(JsonObject().apply {
				addProperty("type", "function")
				addProperty("name", name)
				addProperty("description", function.get("description")?.asString ?: "")
				add(
					"input_schema",
					function.get("parameters")?.deepCopy() ?: JsonObject().apply { addProperty("type", "object") })
			})
		}
		val maxTokens = listOf("max_completion_tokens", "max_tokens", "max_output_tokens")
			.firstNotNullOfOrNull { chat.get(it)?.takeUnless(JsonElement::isJsonNull)?.asInt?.takeIf { n -> n > 0 } }
			?: 65536
		return JsonObject().apply {
			add("config", JsonObject().apply {
				addProperty("workingDir", "/tmp")
				addProperty("date", LocalDate.now(ZoneOffset.UTC).toString())
				addProperty("environment", "qapi3")
				add("structure", JsonArray())
				addProperty("isGitRepo", false)
				addProperty("currentBranch", "")
				addProperty("mainBranch", "")
				addProperty("gitStatus", "")
				add("recentCommits", JsonArray())
			})
			add("memory", null)
			add("taste", null)
			add("skills", null)
			add("params", JsonObject().apply {
				add("model", chat.get("model").deepCopy())
				add("messages", messages)
				add("tools", wireTools)
				addProperty("system", system.joinToString("\n\n"))
				addProperty("max_tokens", maxTokens)
				addProperty("stream", true)
				chat.get("temperature")?.takeUnless(JsonElement::isJsonNull)?.let { add("temperature", it.deepCopy()) }
				if (reasoningEffort != LLMReasoningEffort.NONE) addProperty(
					"reasoning_effort",
					reasoningEffort.wireValue
				)
			})
			addProperty("threadId", UUID.randomUUID().toString())
		}
	}

	private fun wireMessage(role: String, parts: JsonArray) = JsonObject().apply {
		addProperty("role", role)
		add("content", parts)
	}

	private fun contentParts(content: JsonElement?): JsonArray = JsonArray().apply {
		if (content == null || content.isJsonNull) return@apply
		if (content.isJsonPrimitive) {
			add(JsonObject().apply { addProperty("type", "text"); addProperty("text", content.asString) })
			return@apply
		}
		require(content.isJsonArray) { "Unsupported Command Code message content" }
		content.asJsonArray.forEach { element ->
			val part = element.asJsonObject
			when (part.get("type")?.asString) {
				"text", "input_text" -> add(JsonObject().apply {
					addProperty("type", "text")
					addProperty("text", part.get("text")?.asString ?: "")
				})

				"image_url", "input_image" -> {
					val image = part.get("image_url") ?: part.get("image")
					val url = when {
						image?.isJsonPrimitive == true -> image.asString
						image?.isJsonObject == true -> image.asJsonObject.get("url")?.asString
						else -> part.get("image_url")?.asString
					} ?: throw IllegalArgumentException("Image URL is missing")
					val match = Regex(
						"^data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)$",
						RegexOption.IGNORE_CASE
					).matchEntire(url)
						?: throw IllegalArgumentException("Command Code image input requires a base64 data URL")
					add(JsonObject().apply {
						addProperty("type", "image")
						addProperty("image", url)
						addProperty("mimeType", match.groupValues[1].lowercase())
					})
				}

				else -> throw IllegalArgumentException("Unsupported Command Code content part: ${part.get("type")?.asString}")
			}
		}
	}

	private fun textContent(content: JsonElement?): String? {
		if (content == null || content.isJsonNull) return null
		if (content.isJsonPrimitive) return content.asString
		return contentParts(content).mapNotNull { part ->
			part.asJsonObject.takeIf { it.get("type")?.asString == "text" }?.get("text")?.asString
		}.joinToString("")
	}
}
