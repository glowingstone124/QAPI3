package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/** Keeps Anthropic's native content intact between tool/search rounds. */
internal object LLMAnthropicAdapter {
    fun fromChatRequest(
        chatBody: String,
        functionTools: JsonArray,
        reasoningEffort: LLMReasoningEffort,
        stream: Boolean = false,
        webSearch: Boolean = true,
        thinkingMode: String = "enabled",
    ): JsonObject {
        val chat = JsonParser.parseString(chatBody).asJsonObject
        val thinking = reasoningEffort != LLMReasoningEffort.NONE && thinkingMode != "disabled"
        val budget = when (reasoningEffort) {
            LLMReasoningEffort.NONE -> 0
            LLMReasoningEffort.LOW -> 1024
            LLMReasoningEffort.MEDIUM -> 2048
            LLMReasoningEffort.HIGH -> 4096
            LLMReasoningEffort.MAX -> 8192
        }
        val maxTokens = sequenceOf("max_tokens", "max_completion_tokens", "max_output_tokens")
            .mapNotNull { chat.get(it)?.asInt }.firstOrNull()
            ?: if (thinking) maxOf(4096, budget * 2) else 4096
        require(maxTokens > 0) { "max_tokens must be positive for Anthropic completion requests" }
        if (thinking && thinkingMode == "enabled") {
            require(maxTokens > 1024) { "Anthropic thinking requires max_tokens greater than 1024" }
        }
        val system = JsonArray()
        val messages = JsonArray()
        chat.getAsJsonArray("messages").forEach { item ->
            val message = item.asJsonObject
            when (val role = message.get("role")?.asString) {
                "system", "developer" -> convertContent(message.get("content")).forEach { block ->
                    require(block.asJsonObject.get("type")?.asString == "text") { "Anthropic system content must be text" }
                    system.add(block)
                }
                "user", "assistant" -> {
                    val content = convertContent(message.get("content"))
                    message.getAsJsonArray("tool_calls")?.forEach { callItem ->
                        val call = callItem.asJsonObject
                        val function = call.getAsJsonObject("function")
                        content.add(JsonObject().apply {
                            addProperty("type", "tool_use")
                            add("id", call.get("id"))
                            add("name", function.get("name"))
                            add("input", JsonParser.parseString(function.get("arguments")?.asString ?: "{}"))
                        })
                    }
                    appendMessage(messages, role, content)
                }
                "tool" -> appendMessage(messages, "user", JsonArray().apply {
                    add(toolResult(message.get("tool_call_id").asString, message.get("content")?.asString.orEmpty()))
                })
                else -> throw IllegalArgumentException("Unsupported Anthropic message role: $role")
            }
        }
        require(messages.size() > 0) { "Anthropic requests require user/assistant messages" }
        return JsonObject().apply {
            add("model", chat.get("model"))
            addProperty("max_tokens", maxTokens)
            addProperty("stream", stream)
            if (system.size() > 0) add("system", system)
            add("messages", messages)
            add("thinking", JsonObject().apply {
                addProperty("type", if (thinking) thinkingMode else "disabled")
                if (thinking && thinkingMode == "enabled") addProperty("budget_tokens", minOf(budget, maxTokens - 1))
            })
            if (thinking && thinkingMode == "adaptive") add("output_config", JsonObject().apply {
                addProperty("effort", reasoningEffort.wireValue)
            })
            // Sampling parameters are incompatible with manual extended thinking.
            if (!thinking) {
                chat.get("temperature")?.let { add("temperature", it) }
                chat.get("top_p")?.let { add("top_p", it) }
            }
            chat.get("stop")?.takeIf { !it.isJsonNull }?.let { stop ->
                add("stop_sequences", if (stop.isJsonArray) stop.deepCopy() else JsonArray().apply { add(stop.deepCopy()) })
            }
            (chat.get("user") ?: chat.get("user_id"))?.asString?.let { identity ->
                val opaqueId = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                add("metadata", JsonObject().apply { addProperty("user_id", opaqueId) })
            }
            val tools = JsonArray()
            functionTools.forEach { item ->
                val function = item.asJsonObject.getAsJsonObject("function")
                tools.add(JsonObject().apply {
                    add("name", function.get("name"))
                    function.get("description")?.let { add("description", it.deepCopy()) }
                    add("input_schema", function.get("parameters")?.deepCopy() ?: JsonObject().apply { addProperty("type", "object") })
                })
            }
            if (webSearch) tools.add(JsonObject().apply {
                addProperty("type", "web_search_20250305")
                addProperty("name", "web_search")
            })
            if (tools.size() > 0) {
                add("tools", tools)
                add("tool_choice", JsonObject().apply { addProperty("type", "auto") })
            }
        }
    }

    private fun appendMessage(messages: JsonArray, role: String, content: JsonArray) {
        if (content.size() == 0) return
        val previous = messages.lastOrNull()?.asJsonObject
        if (previous?.get("role")?.asString == role) {
            content.forEach { previous.getAsJsonArray("content").add(it) }
        } else messages.add(JsonObject().apply {
            addProperty("role", role)
            add("content", content)
        })
    }

    private fun convertContent(content: JsonElement?): JsonArray = JsonArray().apply {
        if (content == null || content.isJsonNull) return@apply
        if (content.isJsonPrimitive) {
            if (content.asString.isNotEmpty()) add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", content.asString)
            })
            return@apply
        }
        require(content.isJsonArray) { "Anthropic message content must be a string or array" }
        content.asJsonArray.forEach { item ->
            val block = item.asJsonObject
            when (block.get("type")?.asString) {
                "text", "input_text" -> if (block.get("text").asString.isNotEmpty()) add(JsonObject().apply {
                    addProperty("type", "text")
                    add("text", block.get("text").deepCopy())
                })
                "image_url", "input_image" -> {
                    val image = block.get("image_url")
                    val url = if (image.isJsonObject) image.asJsonObject.get("url").asString else image.asString
                    val source = JsonObject()
                    if (url.startsWith("data:")) {
                        val match = Regex("^data:(image/(?:jpeg|png|gif|webp));base64,(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(url)
                            ?: throw IllegalArgumentException("Unsupported Anthropic image data URL")
                        source.addProperty("type", "base64")
                        source.addProperty("media_type", match.groupValues[1])
                        source.addProperty("data", match.groupValues[2])
                    } else {
                        require(url.startsWith("https://") || url.startsWith("http://")) { "Unsupported Anthropic image URL" }
                        source.addProperty("type", "url")
                        source.addProperty("url", url)
                    }
                    add(JsonObject().apply { addProperty("type", "image"); add("source", source) })
                }
                "image", "thinking", "redacted_thinking", "tool_use", "tool_result" -> add(block.deepCopy())
                else -> throw IllegalArgumentException("Unsupported Anthropic content type: ${block.get("type")}")
            }
        }
    }

    fun functionCalls(response: JsonObject): List<ResponseFunctionCall> = response.getAsJsonArray("content").mapNotNull { item ->
        val block = item.asJsonObject
        if (block.get("type")?.asString != "tool_use") return@mapNotNull null
        require(response.get("stop_reason")?.asString == "tool_use") { "Anthropic tool call ended without tool_use stop reason" }
        ResponseFunctionCall(block.get("id").asString, block.get("name").asString, block.get("input").toString())
    }

    fun appendContinuation(request: JsonObject, response: JsonObject, outputs: Map<String, String>) {
        val messages = request.getAsJsonArray("messages")
        messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            add("content", response.getAsJsonArray("content").deepCopy())
        })
        if (outputs.isNotEmpty()) messages.add(JsonObject().apply {
            addProperty("role", "user")
            add("content", JsonArray().apply { outputs.forEach { (id, output) -> add(toolResult(id, output)) } })
        })
    }

    private fun toolResult(id: String, output: String): JsonObject = JsonObject().apply {
        addProperty("type", "tool_result")
        addProperty("tool_use_id", id)
        addProperty("content", output)
        if (runCatching { JsonParser.parseString(output).asJsonObject.has("error") }.getOrDefault(false)) addProperty("is_error", true)
    }

    fun webSearchError(response: JsonObject): String? = response.getAsJsonArray("content").firstNotNullOfOrNull { item ->
        val block = item.asJsonObject
        if (block.get("type")?.asString != "web_search_tool_result") return@firstNotNullOfOrNull null
        val content = block.get("content")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
        if (content.get("type")?.asString == "web_search_tool_result_error")
            "Anthropic Web Search failed: ${content.get("error_code")?.asString ?: "unknown_error"}" else null
    }

    fun citationText(citation: JsonObject): String {
        val url = citation.get("url")?.asString ?: return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ""
        val title = (citation.get("title")?.asString ?: "来源").replace(Regex("[\\[\\]\\r\\n]"), " ")
        return " [$title](${url.replace("(", "%28").replace(")", "%29")})"
    }

    fun text(response: JsonObject): String = response.getAsJsonArray("content").mapNotNull { item ->
        val block = item.asJsonObject
        if (block.get("type")?.asString != "text") return@mapNotNull null
        block.get("text")?.asString.orEmpty() + block.getAsJsonArray("citations")
            ?.joinToString("") { citationText(it.asJsonObject) }.orEmpty()
    }.joinToString("")

    fun toChatCompletion(response: JsonObject): String = JsonObject().apply {
        add("id", response.get("id"))
        addProperty("object", "chat.completion")
        add("model", response.get("model"))
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                addProperty("finish_reason", when (response.get("stop_reason")?.asString) {
                    "max_tokens" -> "length"
                    "tool_use" -> "tool_calls"
                    "refusal" -> "content_filter"
                    else -> "stop"
                })
                add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", text(response)) })
            })
        })
        response.getAsJsonObject("usage")?.let { usage ->
            val read = usage.get("cache_read_input_tokens")?.asInt ?: 0
            val write = usage.get("cache_creation_input_tokens")?.asInt ?: 0
            val input = (usage.get("input_tokens")?.asInt ?: 0) + read + write
            val output = usage.get("output_tokens")?.asInt ?: 0
            add("usage", JsonObject().apply {
                addProperty("prompt_tokens", input)
                addProperty("completion_tokens", output)
                addProperty("total_tokens", input + output)
                addProperty("prompt_cache_hit_tokens", read)
                addProperty("prompt_cache_miss_tokens", input - read)
                addProperty("cache_creation_input_tokens", write)
                addProperty("qapi_api_calls",usage.get("qapi_api_calls")?.asInt ?: 1)
                addProperty("qapi_usage_complete", (usage.get("qapi_usage_complete")?.asBoolean ?: true) &&
                    usage.get("input_tokens")?.isJsonPrimitive == true && usage.get("output_tokens")?.isJsonPrimitive == true)
            })
        }
    }.toString()
}

internal data class AnthropicStreamUpdate(val text: String = "", val phase: String? = null)

/** A message is complete only after message_stop; partial JSON/signatures never escape. */
internal class LLMAnthropicStreamState {
    private var message: JsonObject? = null
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val openBlocks = mutableSetOf<Int>()
    private val partialInputs = mutableMapOf<Int, StringBuilder>()
    var completed: JsonObject? = null
        private set

    fun accept(event: JsonObject): AnthropicStreamUpdate {
        val type = event.get("type")?.asString
        if (type == "ping") return AnthropicStreamUpdate()
        if (type == "error") throw IllegalStateException(event.getAsJsonObject("error")?.get("message")?.asString ?: "Anthropic stream error")
        require(completed == null) { "Anthropic event after message_stop" }
        when (type) {
            "message_start" -> {
                require(message == null) { "Duplicate Anthropic message_start" }
                message = event.getAsJsonObject("message").deepCopy()
            }
            "content_block_start" -> {
                require(message != null) { "Anthropic content before message_start" }
                val index = event.get("index").asInt
                require(!blocks.containsKey(index)) { "Duplicate Anthropic content block" }
                val block = event.getAsJsonObject("content_block").deepCopy()
                blocks[index] = block
                openBlocks.add(index)
                return when (block.get("type")?.asString) {
                    "text" -> AnthropicStreamUpdate(block.get("text")?.asString.orEmpty(), "generating")
                    "thinking" -> AnthropicStreamUpdate(phase = "thinking")
                    "server_tool_use" -> AnthropicStreamUpdate(phase = if (block.get("name")?.asString == "web_search") "web_search" else null)
                    else -> AnthropicStreamUpdate()
                }
            }
            "content_block_delta" -> {
                val index = event.get("index").asInt
                require(index in openBlocks) { "Anthropic delta for closed or missing content block" }
                val block = blocks.getValue(index)
                val delta = event.getAsJsonObject("delta")
                when (delta.get("type")?.asString) {
                    "text_delta" -> {
                        val text = delta.get("text").asString
                        block.addProperty("text", block.get("text")?.asString.orEmpty() + text)
                        return AnthropicStreamUpdate(text, "generating")
                    }
                    "thinking_delta" -> {
                        block.addProperty("thinking", block.get("thinking")?.asString.orEmpty() + delta.get("thinking").asString)
                        return AnthropicStreamUpdate(phase = "thinking")
                    }
                    "signature_delta" -> block.addProperty("signature", block.get("signature")?.asString.orEmpty() + delta.get("signature").asString)
                    "input_json_delta" -> partialInputs.getOrPut(index) { StringBuilder() }.append(delta.get("partial_json").asString)
                    "citations_delta" -> {
                        val citation = delta.getAsJsonObject("citation")
                        if (!block.has("citations")) block.add("citations", JsonArray())
                        block.getAsJsonArray("citations").add(citation.deepCopy())
                        return AnthropicStreamUpdate(LLMAnthropicAdapter.citationText(citation))
                    }
                }
            }
            "content_block_stop" -> {
                val index = event.get("index").asInt
                require(openBlocks.remove(index)) { "Anthropic stopped missing content block" }
                partialInputs.remove(index)?.takeIf { it.isNotEmpty() }?.let {
                    val input = JsonParser.parseString(it.toString())
                    require(input.isJsonObject) { "Anthropic tool input must be a JSON object" }
                    blocks.getValue(index).add("input", input)
                }
            }
            "message_delta" -> {
                val current = requireNotNull(message) { "Anthropic message_delta before message_start" }
                event.getAsJsonObject("delta").entrySet().forEach { (key, value) -> current.add(key, value.deepCopy()) }
                val usage = current.getAsJsonObject("usage") ?: JsonObject().also { current.add("usage", it) }
                // Counts in message_delta are cumulative within this message, not increments.
                event.getAsJsonObject("usage")?.entrySet()?.forEach { (key, value) -> usage.add(key, value.deepCopy()) }
            }
            "message_stop" -> {
                val current = requireNotNull(message) { "Anthropic message_stop before message_start" }
                require(openBlocks.isEmpty()) { "Anthropic message stopped with incomplete content blocks" }
                require(!current.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString.isNullOrBlank()) { "Anthropic message has no stop reason" }
                current.add("content", JsonArray().apply { blocks.values.forEach { add(it.deepCopy()) } })
                completed = current
            }
        }
        return AnthropicStreamUpdate()
    }
}
