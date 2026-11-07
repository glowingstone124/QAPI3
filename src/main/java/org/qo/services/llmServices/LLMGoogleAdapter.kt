package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

internal object LLMGoogleAdapter : LLMAdapter {
	override val protocol: LLMProtocol = LLMProtocol.GOOGLE

	internal const val SKIP_THOUGHT_SIGNATURE_VALIDATOR = "skip_thought_signature_validator"

	override fun adapt(request: LLMAdapterRequest): JsonObject = fromChatRequest(
		request.chat.toString(),
		request.functionTools,
		request.reasoningEffort,
		request.stream,
		request.webSearch,
		request.thinkingMode,
	)

	fun fromChatRequest(
		chatBody: String,
		functionTools: JsonArray,
		reasoningEffort: LLMReasoningEffort,
		stream: Boolean = false,
		webSearch: Boolean = true,
		thinkingMode: String = "enabled",
	): JsonObject {
		val chat = JsonParser.parseString(chatBody).asJsonObject
		val modelName = chat.get("model")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
		val maxTokens = sequenceOf("max_output_tokens", "max_completion_tokens", "max_tokens")
			.mapNotNull { key -> chat.get(key)?.takeIf { !it.isJsonNull }?.asInt }
			.firstOrNull()
		if (maxTokens != null) {
			require(maxTokens > 0) { "maxOutputTokens must be positive for Google completion requests" }
		}

		val systemParts = JsonArray()
		val contents = JsonArray()
		val callIdToName = mutableMapOf<String, String>()

		val rawMessages = chat.getAsJsonArray("messages")
			?: throw IllegalArgumentException("Google requests require messages array")
		rawMessages.forEach { item ->
			val message = item.takeIf { it.isJsonObject }?.asJsonObject
				?: throw IllegalArgumentException("Google messages must contain objects")
			when (val role = message.get("role")?.asString) {
				"system", "developer" -> convertContentParts(message.get("content"), allowEmptyText = false).forEach { part ->
					val obj = part.asJsonObject
					require(obj.has("text") && obj.size() == 1) { "Google system instruction content must be text" }
					systemParts.add(obj)
				}

				"user" -> {
					val parts = convertContentParts(message.get("content"), allowEmptyText = true)
					appendContent(contents, "user", parts)
				}

				"assistant", "model" -> {
					val parts = convertContentParts(message.get("content"), allowEmptyText = false)
					val toolCalls = message.getAsJsonArray("tool_calls")
					if (toolCalls != null && toolCalls.size() > 0) {
						val hasNativeSignature = parts.any { it.asJsonObject.has("thoughtSignature") } ||
							toolCalls.any {
								val callObj = it.takeIf(JsonElement::isJsonObject)?.asJsonObject
								callObj?.has("thoughtSignature") == true || callObj?.has("thought_signature") == true
							}
						toolCalls.forEachIndexed { index, callItem ->
							val call = callItem.asJsonObject
							val id = call.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
							val function = call.getAsJsonObject("function")
								?: throw IllegalArgumentException("Google tool call is missing function")
							val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
								?: throw IllegalArgumentException("Google tool call is missing function name")
							if (id != null) callIdToName[id] = name
							val rawArgs = function.get("arguments")?.takeIf { !it.isJsonNull }?.asString
							val argsObj = if (rawArgs.isNullOrBlank()) {
								JsonObject()
							} else {
								val parsed = JsonParser.parseString(rawArgs)
								require(parsed.isJsonObject) { "Google functionCall arguments must be a JSON object" }
								parsed.asJsonObject
							}
							val explicitSig = (call.get("thoughtSignature") ?: call.get("thought_signature"))
								?.takeIf { !it.isJsonNull }
								?.asString
								?.takeIf { it.isNotBlank() }
							parts.add(JsonObject().apply {
								add("functionCall", JsonObject().apply {
									if (id != null) addProperty("id", id)
									addProperty("name", name)
									add("args", argsObj)
								})
								if (explicitSig != null) {
									addProperty("thoughtSignature", explicitSig)
								} else if (!hasNativeSignature && index == 0) {
									addProperty("thoughtSignature", SKIP_THOUGHT_SIGNATURE_VALIDATOR)
								}
							})
						}
					}
					appendContent(contents, "model", parts)
				}

				"tool", "function" -> {
					val callId = message.get("tool_call_id")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
					val explicitName = message.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
					val resolvedName = explicitName ?: callId?.let(callIdToName::get)
						?: throw IllegalArgumentException("Google tool result '${callId ?: ""}' has no matching function name")
					val output = message.get("content")?.takeIf { !it.isJsonNull }?.let { content ->
						if (content.isJsonPrimitive) content.asString else content.toString()
					}.orEmpty()
					appendContent(contents, "user", JsonArray().apply {
						add(functionResponsePart(callId, resolvedName, output))
					})
				}

				else -> throw IllegalArgumentException("Unsupported Google message role: $role")
			}
		}
		require(contents.size() > 0) { "Google requests require user/assistant messages" }

		return JsonObject().apply {
			if (systemParts.size() > 0) {
				add("systemInstruction", JsonObject().apply { add("parts", systemParts) })
			}
			add("contents", contents)

			val declarations = JsonArray()
			functionTools.forEach { item ->
				val toolObj = item.asJsonObject
				val function = toolObj.getAsJsonObject("function") ?: toolObj
				val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
					?.takeIf { it.isNotEmpty() }
					?: throw IllegalArgumentException("Google function tool is missing name")
				declarations.add(JsonObject().apply {
					addProperty("name", name)
					function.get("description")?.takeIf { !it.isJsonNull }?.let { add("description", it.deepCopy()) }
					val rawParams = function.get("parameters") ?: function.get("parametersJsonSchema")
					val cleanSchema = rawParams?.takeIf { it.isJsonObject }?.let(::sanitizeJsonSchema)
						?: JsonObject().apply { addProperty("type", "object") }
					add("parametersJsonSchema", cleanSchema)
				})
			}

			val tools = JsonArray()
			if (declarations.size() > 0) {
				tools.add(JsonObject().apply { add("functionDeclarations", declarations) })
			}
			if (webSearch) {
				tools.add(JsonObject().apply { add("googleSearch", JsonObject()) })
			}
			if (tools.size() > 0) {
				add("tools", tools)
			}
			if (declarations.size() > 0) {
				add("toolConfig", JsonObject().apply {
					add("functionCallingConfig", JsonObject().apply {
						addProperty("mode", "AUTO")
					})
				})
			}

			val generationConfig = JsonObject().apply {
				maxTokens?.let { addProperty("maxOutputTokens", it) }
				chat.get("temperature")?.takeIf { !it.isJsonNull }?.let { add("temperature", it.deepCopy()) }
				chat.get("top_p")?.takeIf { !it.isJsonNull }?.let { add("topP", it.deepCopy()) }
				chat.get("stop")?.takeIf { !it.isJsonNull }?.let { stop ->
					add(
						"stopSequences",
						if (stop.isJsonArray) stop.deepCopy() else JsonArray().apply { add(stop.deepCopy()) },
					)
				}
				chat.get("response_format")?.takeIf { it.isJsonObject }?.asJsonObject?.let { format ->
					when (format.get("type")?.takeIf { !it.isJsonNull }?.asString) {
						"json_object" -> addProperty("responseMimeType", "application/json")
						"json_schema" -> {
							addProperty("responseMimeType", "application/json")
							format.getAsJsonObject("json_schema")?.get("schema")?.takeIf { it.isJsonObject }?.let { schema ->
								add("responseJsonSchema", sanitizeJsonSchema(schema))
							}
						}
					}
				}
				buildThinkingConfig(modelName, reasoningEffort, thinkingMode, maxTokens)?.let { thinking ->
					add("thinkingConfig", thinking)
				}
			}
			if (generationConfig.size() > 0) {
				add("generationConfig", generationConfig)
			}
		}
	}

	private fun buildThinkingConfig(
		modelName: String,
		reasoningEffort: LLMReasoningEffort,
		thinkingMode: String,
		maxTokens: Int?,
	): JsonObject? {
		val lowerModel = modelName.lowercase(Locale.ROOT)
		if (lowerModel.contains("gemini-1.") || (lowerModel.contains("gemini-2.0") && !lowerModel.contains("thinking"))) {
			return null
		}
		val thinking = reasoningEffort != LLMReasoningEffort.NONE && thinkingMode != "disabled"
		if (lowerModel.contains("gemini-2.5")) {
			return JsonObject().apply {
				if (!thinking) {
					addProperty("thinkingBudget", if (lowerModel.contains("pro")) 128 else 0)
				} else if (thinkingMode == "adaptive") {
					addProperty("thinkingBudget", -1)
					addProperty("includeThoughts", true)
				} else {
					val rawBudget = when (reasoningEffort) {
						LLMReasoningEffort.NONE -> 0
						LLMReasoningEffort.LOW -> 1024
						LLMReasoningEffort.MEDIUM -> 2048
						LLMReasoningEffort.HIGH -> 4096
						LLMReasoningEffort.MAX -> 8192
					}
					val capped = if (maxTokens != null && maxTokens > 1) minOf(rawBudget, maxTokens - 1) else rawBudget
					addProperty("thinkingBudget", capped)
					addProperty("includeThoughts", true)
				}
			}
		}
		return JsonObject().apply {
			if (!thinking) {
				addProperty("thinkingLevel", if (lowerModel.contains("flash")) "MINIMAL" else "LOW")
			} else {
				val level = when (reasoningEffort) {
					LLMReasoningEffort.NONE, LLMReasoningEffort.LOW -> "LOW"
					LLMReasoningEffort.MEDIUM -> "MEDIUM"
					LLMReasoningEffort.HIGH, LLMReasoningEffort.MAX -> "HIGH"
				}
				addProperty("thinkingLevel", level)
				addProperty("includeThoughts", true)
			}
		}
	}

	private fun sanitizeJsonSchema(element: JsonElement): JsonElement = when {
		element.isJsonObject -> JsonObject().apply {
			element.asJsonObject.entrySet().forEach { (key, value) ->
				if (key !in setOf("\$schema", "\$id")) {
					add(key, sanitizeJsonSchema(value))
				}
			}
		}
		element.isJsonArray -> JsonArray().apply {
			element.asJsonArray.forEach { add(sanitizeJsonSchema(it)) }
		}
		else -> element.deepCopy()
	}

	private fun appendContent(contents: JsonArray, role: String, parts: JsonArray) {
		if (parts.size() == 0) return
		val previous = contents.lastOrNull()?.asJsonObject
		if (previous?.get("role")?.asString == role) {
			parts.forEach { previous.getAsJsonArray("parts").add(it) }
		} else {
			contents.add(JsonObject().apply {
				addProperty("role", role)
				add("parts", parts)
			})
		}
	}

	private fun convertContentParts(content: JsonElement?, allowEmptyText: Boolean): JsonArray = JsonArray().apply {
		if (content == null || content.isJsonNull) return@apply
		if (content.isJsonPrimitive) {
			val text = content.asString
			if (text.isNotEmpty() || allowEmptyText) {
				add(JsonObject().apply { addProperty("text", text) })
			}
			return@apply
		}
		require(content.isJsonArray) { "Google message content must be a string or array" }
		content.asJsonArray.forEach { item ->
			val block = item.takeIf { it.isJsonObject }?.asJsonObject
				?: throw IllegalArgumentException("Google content parts must be objects")
			val type = block.get("type")?.takeIf { !it.isJsonNull }?.asString
			when {
				type in setOf("text", "input_text") -> {
					val text = block.get("text")?.takeIf { !it.isJsonNull }?.asString
						?: throw IllegalArgumentException("Google text part is missing text")
					if (text.isNotEmpty() || allowEmptyText) {
						add(JsonObject().apply { addProperty("text", text) })
					}
				}

				type in setOf("image_url", "input_image") -> add(convertImagePart(block))

				type == null && (block.has("text") || block.has("inlineData") || block.has("fileData") ||
					block.has("functionCall") || block.has("functionResponse") || block.has("thoughtSignature")) -> {
					add(block.deepCopy())
				}

				else -> throw IllegalArgumentException("Unsupported Google content type: $type")
			}
		}
	}

	private fun convertImagePart(block: JsonObject): JsonObject {
		val imageElement = block.get("image_url")
			?: throw IllegalArgumentException("Google image part is missing image_url")
		val url = when {
			imageElement.isJsonPrimitive -> imageElement.asString
			imageElement.isJsonObject -> imageElement.asJsonObject.get("url")?.takeIf { !it.isJsonNull }?.asString
				?: throw IllegalArgumentException("Google image_url object is missing url")
			else -> throw IllegalArgumentException("Google image_url must be a string or object")
		}.trim()
		require(url.isNotEmpty()) { "Google image_url cannot be blank" }

		if (url.startsWith("data:")) {
			val match = Regex(
				"^data:(image/(?:jpeg|jpg|png|gif|webp|heic|heif));base64,(.+)$",
				RegexOption.DOT_MATCHES_ALL,
			).matchEntire(url) ?: throw IllegalArgumentException("Unsupported Google image data URL")
			val rawMime = match.groupValues[1].lowercase(Locale.ROOT)
			val mimeType = if (rawMime == "image/jpg") "image/jpeg" else rawMime
			return JsonObject().apply {
				add("inlineData", JsonObject().apply {
					addProperty("mimeType", mimeType)
					addProperty("data", match.groupValues[2])
				})
			}
		}

		require(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("gs://")) {
			"Unsupported Google image URL"
		}
		return JsonObject().apply {
			add("fileData", JsonObject().apply {
				addProperty("mimeType", inferImageMimeType(url))
				addProperty("fileUri", url)
			})
		}
	}

	private fun inferImageMimeType(url: String): String {
		val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
		return when {
			cleanPath.endsWith(".png") -> "image/png"
			cleanPath.endsWith(".gif") -> "image/gif"
			cleanPath.endsWith(".webp") -> "image/webp"
			cleanPath.endsWith(".heic") -> "image/heic"
			cleanPath.endsWith(".heif") -> "image/heif"
			else -> "image/jpeg"
		}
	}

	private fun functionResponsePart(id: String?, name: String, output: String): JsonObject {
		val responseObj = runCatching {
			JsonParser.parseString(output).takeIf { it.isJsonObject }?.asJsonObject
		}.getOrNull() ?: JsonObject().apply {
			addProperty("output", output)
		}
		return JsonObject().apply {
			add("functionResponse", JsonObject().apply {
				if (!id.isNullOrBlank()) addProperty("id", id)
				addProperty("name", name)
				add("response", responseObj)
			})
		}
	}

	private fun firstCandidate(response: JsonObject): JsonObject? =
		response.getAsJsonArray("candidates")
			?.firstOrNull()
			?.takeIf { it.isJsonObject }
			?.asJsonObject

	fun functionCalls(response: JsonObject): List<ResponseFunctionCall> {
		val parts = firstCandidate(response)
			?.getAsJsonObject("content")
			?.getAsJsonArray("parts")
			?: return emptyList()
		var index = 0
		return parts.mapNotNull { item ->
			val part = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			val fc = part.getAsJsonObject("functionCall") ?: return@mapNotNull null
			index++
			val name = fc.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
				?: throw IllegalStateException("Google functionCall part is missing name")
			val callId = fc.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
				?: "call_$index"
			val args = fc.get("args")?.takeIf { !it.isJsonNull }?.let {
				require(it.isJsonObject) { "Google functionCall args must be a JSON object" }
				it.toString()
			} ?: "{}"
			ResponseFunctionCall(callId, name, args)
		}
	}

	fun appendContinuation(request: JsonObject, response: JsonObject, outputs: Map<String, String>) {
		val candidateContent = firstCandidate(response)?.getAsJsonObject("content")?.deepCopy()
			?: throw IllegalArgumentException("Google response candidate is missing content")
		if (!candidateContent.has("role")) {
			candidateContent.addProperty("role", "model")
		}
		val contents = request.getAsJsonArray("contents")
		contents.add(candidateContent)
		if (outputs.isEmpty()) return

		val responseParts = JsonArray()
		var index = 0
		candidateContent.getAsJsonArray("parts")?.forEach { item ->
			val fc = item.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("functionCall") ?: return@forEach
			index++
			val nativeId = fc.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
			val lookupId = nativeId ?: "call_$index"
			val name = fc.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
			val output = outputs[lookupId] ?: return@forEach
			responseParts.add(functionResponsePart(nativeId, name, output))
		}
		if (responseParts.size() > 0) {
			appendContent(contents, "user", responseParts)
		}
	}

	fun blockError(response: JsonObject): String? {
		response.getAsJsonObject("error")?.let { err ->
			val msg = err.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "unknown_error"
			return "Google upstream error: $msg"
		}
		response.getAsJsonObject("promptFeedback")
			?.get("blockReason")
			?.takeIf { !it.isJsonNull }
			?.asString
			?.takeIf { it.isNotBlank() && it != "BLOCK_REASON_UNSPECIFIED" }
			?.let { reason ->
				return "Google prompt blocked: $reason"
			}
		val finishReason = firstCandidate(response)?.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
		if (finishReason in setOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL")) {
			return "Google tool call failed: $finishReason"
		}
		return null
	}

	fun citationText(title: String?, url: String?): String {
		val validUrl = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return ""
		val cleanTitle = (title?.takeIf { it.isNotBlank() } ?: "来源").replace(Regex("[\\[\\]\\r\\n]"), " ")
		return " [$cleanTitle](${validUrl.replace("(", "%28").replace(")", "%29")})"
	}

	internal fun groundingCitations(groundingMetadata: JsonObject?, seenUrls: MutableSet<String> = linkedSetOf()): String {
		val chunks = groundingMetadata?.getAsJsonArray("groundingChunks") ?: return ""
		val formatted = StringBuilder()
		chunks.forEach { item ->
			val web = item.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("web") ?: return@forEach
			val uri = web.get("uri")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: return@forEach
			if (!seenUrls.add(uri)) return@forEach
			val title = web.get("title")?.takeIf { !it.isJsonNull }?.asString
			formatted.append(citationText(title, uri))
		}
		return formatted.toString()
	}

	fun text(response: JsonObject): String {
		val candidate = firstCandidate(response) ?: return ""
		val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts")
		val body = parts?.mapNotNull { item ->
			val part = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
			if (part.get("thought")?.takeIf { !it.isJsonNull }?.asBoolean == true) return@mapNotNull null
			part.get("text")?.takeIf { !it.isJsonNull }?.asString
		}?.joinToString("").orEmpty()
		val citations = groundingCitations(candidate.getAsJsonObject("groundingMetadata"))
		return body + citations
	}

	fun toChatCompletion(response: JsonObject, fallbackModel: String? = null): String {
		val candidate = firstCandidate(response)
		val calls = functionCalls(response)
		val rawFinish = candidate?.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
		val finishReason = when {
			rawFinish == "MAX_TOKENS" -> "length"
			calls.isNotEmpty() -> "tool_calls"
			rawFinish in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY") -> "content_filter"
			else -> "stop"
		}
		val model = response.get("modelVersion")?.takeIf { !it.isJsonNull }?.asString ?: fallbackModel
		return JsonObject().apply {
			addProperty("id", response.get("responseId")?.takeIf { !it.isJsonNull }?.asString ?: "chatcmpl-google")
			addProperty("object", "chat.completion")
			model?.let { addProperty("model", it) }
			add("choices", JsonArray().apply {
				add(JsonObject().apply {
					addProperty("index", 0)
					addProperty("finish_reason", finishReason)
					add("message", JsonObject().apply {
						addProperty("role", "assistant")
						addProperty("content", text(response))
						if (calls.isNotEmpty()) {
							add("tool_calls", nativeToolCalls(calls))
						}
					})
				})
			})
			(response.getAsJsonObject("usageMetadata") ?: response.getAsJsonObject("usage"))?.let { usage ->
				val prompt = (usage.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0) +
					(usage.get("toolUsePromptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0)
				val candidatesTokens = usage.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
				val thoughtsTokens = usage.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
				val completion = candidatesTokens + thoughtsTokens
				val cachedElem = usage.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }
				val cached = cachedElem?.asInt ?: 0
				val complete = (usage.get("qapi_usage_complete")?.takeIf { !it.isJsonNull }?.asBoolean ?: true) &&
					(usage.get("promptTokenCount")?.isJsonPrimitive == true || usage.get("totalTokenCount")?.isJsonPrimitive == true)
				add("usage", JsonObject().apply {
					addProperty("prompt_tokens", prompt)
					addProperty("completion_tokens", completion)
					addProperty("total_tokens", prompt + completion)
					if (cachedElem != null || cached > 0) {
						addProperty("prompt_cache_hit_tokens", cached)
						addProperty("prompt_cache_miss_tokens", (prompt - cached).coerceAtLeast(0))
						add("prompt_tokens_details", JsonObject().apply { addProperty("cached_tokens", cached) })
					}
					if (thoughtsTokens > 0 || usage.has("thoughtsTokenCount")) {
						add("completion_tokens_details", JsonObject().apply {
							addProperty("reasoning_tokens", thoughtsTokens.toLong())
						})
					}
					addProperty("qapi_api_calls", usage.get("qapi_api_calls")?.takeIf { !it.isJsonNull }?.asInt ?: 1)
					addProperty("qapi_usage_complete", complete)
				})
			}
		}.toString()
	}
}

internal data class GoogleStreamUpdate(val text: String = "", val phase: String? = null)

/** Reassembles Gemini SSE GenerateContentResponse chunks while retaining native parts and thought signatures. */
internal class LLMGoogleStreamState {
	private var responseId: String? = null
	private var modelVersion: String? = null
	private var finishReason: String? = null
	private var promptFeedback: JsonObject? = null
	private var usageMetadata: JsonObject? = null
	private var groundingMetadata: JsonObject? = null
	private val parts = JsonArray()
	private val seenCitationUrls = linkedSetOf<String>()

	var completed: JsonObject? = null
		private set

	fun accept(event: JsonObject): GoogleStreamUpdate {
		require(completed == null) { "Google stream event received after completion" }
		event.getAsJsonObject("error")?.let { err ->
			throw IllegalStateException(err.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Google stream error")
		}
		event.get("responseId")?.takeIf { !it.isJsonNull }?.asString?.let { responseId = it }
		event.get("modelVersion")?.takeIf { !it.isJsonNull }?.asString?.let { modelVersion = it }
		event.getAsJsonObject("promptFeedback")?.let { promptFeedback = it.deepCopy() }
		event.getAsJsonObject("usageMetadata")?.let { incoming ->
			val current = usageMetadata ?: JsonObject().also { usageMetadata = it }
			// Counts in Gemini usageMetadata chunks are cumulative within one request.
			incoming.entrySet().forEach { (key, value) -> current.add(key, value.deepCopy()) }
		}

		val candidate = event.getAsJsonArray("candidates")
			?.firstOrNull()
			?.takeIf { it.isJsonObject }
			?.asJsonObject
			?: return GoogleStreamUpdate()

		candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
			finishReason = it
		}

		var phase: String? = null
		val deltaText = StringBuilder()

		candidate.getAsJsonObject("content")?.getAsJsonArray("parts")?.forEach { item ->
			val part = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
			parts.add(part.deepCopy())
			val isThought = part.get("thought")?.takeIf { !it.isJsonNull }?.asBoolean == true
			if (isThought) {
				if (phase == null) phase = "thinking"
			} else if (part.has("text") && !part.get("text").isJsonNull) {
				val text = part.get("text").asString
				if (text.isNotEmpty()) {
					deltaText.append(text)
					phase = "generating"
				}
			}
		}

		candidate.getAsJsonObject("groundingMetadata")?.let { gm ->
			groundingMetadata = mergeGroundingMetadata(groundingMetadata, gm)
			if (gm.getAsJsonArray("webSearchQueries")?.size()?.let { it > 0 } == true && deltaText.isEmpty() && phase == null) {
				phase = "web_search"
			}
			val citations = LLMGoogleAdapter.groundingCitations(gm, seenCitationUrls)
			if (citations.isNotEmpty()) {
				deltaText.append(citations)
				if (phase == null) phase = "generating"
			}
		}

		return GoogleStreamUpdate(deltaText.toString(), phase)
	}

	fun finish(): JsonObject {
		completed?.let { return it }
		val blocked = promptFeedback?.get("blockReason")?.takeIf { !it.isJsonNull }?.asString
			?.takeIf { it.isNotBlank() && it != "BLOCK_REASON_UNSPECIFIED" }
		if (blocked == null && finishReason.isNullOrBlank()) {
			throw IllegalStateException("Google stream ended without finishReason")
		}
		return JsonObject().apply {
			responseId?.let { addProperty("responseId", it) }
			modelVersion?.let { addProperty("modelVersion", it) }
			promptFeedback?.let { add("promptFeedback", it.deepCopy()) }
			if (blocked == null || parts.size() > 0 || !finishReason.isNullOrBlank()) {
				add("candidates", JsonArray().apply {
					add(JsonObject().apply {
						add("content", JsonObject().apply {
							addProperty("role", "model")
							add("parts", parts.deepCopy())
						})
						finishReason?.let { addProperty("finishReason", it) }
						groundingMetadata?.let { add("groundingMetadata", it.deepCopy()) }
					})
				})
			}
			usageMetadata?.let { add("usageMetadata", it.deepCopy()) }
		}.also { completed = it }
	}

	private fun mergeGroundingMetadata(existing: JsonObject?, incoming: JsonObject): JsonObject {
		if (existing == null) return incoming.deepCopy()
		val merged = existing.deepCopy()
		incoming.entrySet().forEach { (key, value) ->
			if (key == "groundingChunks" && merged.has("groundingChunks") && value.isJsonArray) {
				val target = merged.getAsJsonArray("groundingChunks")
				value.asJsonArray.forEach { target.add(it.deepCopy()) }
			} else {
				merged.add(key, value.deepCopy())
			}
		}
		return merged
	}
}
