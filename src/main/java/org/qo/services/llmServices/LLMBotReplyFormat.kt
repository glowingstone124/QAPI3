package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Opt-in QQ delivery format; provider content and stored history remain readable plain text. */
internal object LLMBotReplyFormat {
    const val FORMAT = "messages_v1"
    const val BREAK = "<<<QBOT_MESSAGE_BREAK>>>"
    const val MAX_MESSAGES = 3

    val systemRules = """
        QQ 消息发送方式：
        - 自然接话，日常聊天优先简短回应。你自行决定本轮发一条还是两三条消息，不要每次都拆分，也不要为了凑消息补充废话。
        - 需要先回应再补充、轻松接话或追问时，可以分成 2–3 条各自完整的短消息，每条可包含一句或几句。
        - 仅在两条独立消息之间单独一行输出 $BREAK。不在开头或末尾输出，不解释该标记，不添加序号或 JSON 包装；没有标记即发送一条。
        - 不要按标点机械拆句，不要把同一段代码、引用、清单或紧密相连的说明拆到不同消息。用户要求完整长文、精确格式或代码时优先保持完整。
        - 该标记仅是服务端约定的消息边界，不是工具调用或装饰符号；最终会被服务端移除。
    """.trimIndent()

    fun consume(request: JsonObject, source: String?): Boolean {
        val format = request.remove("qbot_reply_format")
        return source == LLMSource.QQ.value && format?.isJsonPrimitive == true
            && format.asJsonPrimitive.isString && format.asString == FORMAT
    }

    fun withRequestedFormat(body: String, header: String?): String {
        if (header != FORMAT) return body
        return JsonParser.parseString(body).asJsonObject.apply {
            addProperty("qbot_reply_format", FORMAT)
        }.toString()
    }

    fun formatResponse(body: String): String {
        val response = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        val choices = response.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return body
        for (choice in choices) {
            val message = choice.takeIf { it.isJsonObject }?.asJsonObject?.get("message")
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val content = message.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: continue
            val parts = split(content)
            if (parts.isEmpty()) continue
            message.addProperty("content", parts.joinToString("\n\n"))
            message.add("qq_messages", JsonArray().apply { parts.forEach(::add) })
        }
        return response.toString()
    }

    internal fun split(content: String): List<String> {
        // Only standalone lines are framing. Literal inline examples and fenced code are untouched.
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var fence: String? = null
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (fence == null && trimmed == BREAK) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                current.setLength(0)
                continue
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                fence = if (fence == marker) null else fence ?: marker
            }
            current.append(line).append('\n')
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        return if (parts.size <= MAX_MESSAGES) parts
            else parts.take(MAX_MESSAGES - 1) + parts.drop(MAX_MESSAGES - 1).joinToString("\n\n")
    }
}
