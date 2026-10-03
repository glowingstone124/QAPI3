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
        QQ 群聊多消息气泡规则：
        - 你正在 QQ 群聊中与人日常交流，请保持自然亲切、口语化的聊天风格。
        - 积极使用多消息气泡分条发送：日常交流中人们习惯连续发送 2–3 条短消息，而不是一次性发出一长串大段文字。当你的回复包含两个以上的动作或层次时（例如：先接话/打招呼/表态 + 再回答核心问题；先回答 + 再补充追问或提醒；或者多句简短的连贯闲聊），应当主动拆分为 2–3 条各自独立的短消息气泡。
        - 气泡切分标记：在两条独立消息气泡之间，单独一行输出 --- 或 $BREAK 进行切分（系统会自动将其转换为独立消息气泡分条发送并移除标记）。不在开头或末尾输出切分标记，不输出多余的解释。
        - 每一条消息气泡应是简短自然的一句话或两三句话，如同连续按回车发送的几条聊天消息。不要按标点机械拆句，不要把同一段代码、引用、清单或紧密相连的说明拆到不同消息。
        - 工具调用时的面向用户输出：在任何需要调用工具的轮次（如画图、联网搜索、查询状态等），绝对不要保持沉默！必须在同一回复中先输出一句自然亲切的应答（例如告诉用户正在画、正在查，或先接住用户的话），再触发工具调用。
        - 保持完整单条消息的例外：不要每次都拆分，当用户明确要求长文、小说、长篇分析、代码实现或清单表格时，优先保持完整，不在代码和清单中插入切分标记。
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

    internal fun isBreakMarker(line: String): Boolean {
        val trimmed = line.trim()
        val stripped = trimmed.removeSurrounding("**").removeSurrounding("*").trim()
        if (stripped == BREAK || stripped == "<<<BREAK>>>" || stripped == "[BREAK]" || stripped == "<break/>" || stripped == "<break>") {
            return true
        }
        return trimmed.matches(Regex("""^[-*_]{3,}$"""))
    }

    internal fun split(content: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var fence: String? = null
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                fence = if (fence == marker) null else fence ?: marker
            }
            if (fence == null && isBreakMarker(trimmed)) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                current.setLength(0)
                continue
            }
            current.append(line).append('\n')
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        return if (parts.size <= MAX_MESSAGES) parts
            else parts.take(MAX_MESSAGES - 1) + parts.drop(MAX_MESSAGES - 1).joinToString("\n\n")
    }
}
