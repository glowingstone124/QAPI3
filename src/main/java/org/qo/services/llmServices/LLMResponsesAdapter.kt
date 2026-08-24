package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object LLMResponsesAdapter {
    fun fromChatRequest(chatBody: String, functionTools: JsonArray, enableWebSearch: Boolean): JsonObject {
        val chat = JsonParser.parseString(chatBody).asJsonObject
        return JsonObject().apply {
            add("model", chat.get("model"))
            add("input", convertMessages(chat.getAsJsonArray("messages")))
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

    private fun convertMessages(messages: JsonArray): JsonArray {
        return JsonArray().apply {
            messages.forEach { item ->
                val message = item.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("messages must contain objects")
                val converted = message.deepCopy()
                message.get("content")?.let { content ->
                    converted.add("content", convertContent(content))
                }
                add(converted)
            }
        }
    }

    private fun convertContent(content: JsonElement): JsonElement {
        // Keep the old simple-string form unchanged. Responses accepts text message
        // content directly, and this also preserves compatibility with the current
        // upstream implementation.
        if (!content.isJsonArray) {
            return content.deepCopy()
        }

        return JsonArray().apply {
            content.asJsonArray.forEach { part ->
                val obj = part.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("message content parts must be objects")

                when (obj.get("type")?.asString) {
                    "text" -> add(JsonObject().apply {
                        addProperty("type", "input_text")
                        val text = obj.get("text")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                            ?: throw IllegalArgumentException("text content part is missing text")
                        addProperty("text", text)
                    })

                    "image_url" -> add(convertImagePart(obj))

                    // Allow already-normalized Responses content as an internal
                    // compatibility path.
                    "input_text", "input_image" -> add(obj.deepCopy())

                    else -> throw IllegalArgumentException(
                        "Unsupported message content type: ${obj.get("type")?.asString}"
                    )
                }
            }
        }
    }

    private fun convertImagePart(part: JsonObject): JsonObject {
        val imageUrl = part.get("image_url")
            ?: throw IllegalArgumentException("image_url content part is missing image_url")

        val url: String
        var detail: String? = null

        when {
            imageUrl.isJsonPrimitive -> {
                url = imageUrl.asString
            }

            imageUrl.isJsonObject -> {
                val image = imageUrl.asJsonObject
                url = image.get("url")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?: throw IllegalArgumentException("image_url content part is missing url")
                detail = image.get("detail")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }

            else -> throw IllegalArgumentException("image_url must be a string or object")
        }

        if (detail == null) {
            detail = part.get("detail")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
        }

        require(url.isNotBlank()) {
            "image_url cannot be blank"
        }

        return JsonObject().apply {
            addProperty("type", "input_image")
            addProperty("image_url", url)
            detail?.takeIf { it.isNotBlank() }?.let { addProperty("detail", it) }
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
        val cachedTokens = usage?.get("prompt_cache_hit_tokens")?.asInt
            ?: usage?.getAsJsonObject("input_tokens_details")?.get("cached_tokens")?.asInt
        val cacheMissTokens = usage?.get("prompt_cache_miss_tokens")?.asInt
            ?: cachedTokens?.let { (promptTokens - it).coerceAtLeast(0) }
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
                cachedTokens?.let {
                    addProperty("prompt_cache_hit_tokens", it)
                    add("prompt_tokens_details", JsonObject().apply { addProperty("cached_tokens", it) })
                }
                cacheMissTokens?.let { addProperty("prompt_cache_miss_tokens", it) }
            })
        }.toString()
    }

    private fun extractText(response: JsonObject): String {
        val parts = mutableListOf<String>()
        response.getAsJsonArray("output")?.forEach { item ->
            val message = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (message.get("type")?.asString != "message") return@forEach
            message.getAsJsonArray("content")?.forEach { block ->
                val text = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                if (text.get("type")?.asString == "output_text") {
                    text.get("text")?.asString
                        ?.let { stripUrlCitations(it, text.getAsJsonArray("annotations")) }
                        ?.takeIf { it.isNotBlank() }
                        ?.let(parts::add)
                }
            }
        }
        return parts.joinToString("\n\n").trim()
    }

    private fun stripUrlCitations(text: String, annotations: JsonArray?): String {
        if (annotations == null) return text
        val ranges = annotations.mapNotNull { annotation ->
            val citation = annotation.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (citation.get("type")?.asString != "url_citation") return@mapNotNull null
            val start = citation.get("start_index")?.asInt ?: return@mapNotNull null
            val end = citation.get("end_index")?.asInt ?: return@mapNotNull null
            if (start !in 0..<end || end > text.length) return@mapNotNull null
            start until end
        }.sortedByDescending { it.first }

        return StringBuilder(text).apply {
            ranges.forEach { range -> delete(range.first, range.last + 1) }
        }.toString()
            .replace(Regex("""\s+[，,；;：:]"""), { it.value.trimStart() })
            .replace(Regex("""[（(]\s*[）)]"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }
}

data class ResponseFunctionCall(val callId: String, val name: String, val arguments: String?)
