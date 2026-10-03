package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMBotReplyFormatTest {
    @Test
    fun `delivery header enables format without replacing the original question`() {
        val body = """{"messages":[{"role":"user","content":"question"}]}"""
        assertEquals(body, LLMBotReplyFormat.withRequestedFormat(body, null))
        assertEquals(body, LLMBotReplyFormat.withRequestedFormat(body, "unsupported"))
        val request = JsonParser.parseString(LLMBotReplyFormat.withRequestedFormat(body, "messages_v1")).asJsonObject
        assertEquals("messages_v1", request.get("qbot_reply_format").asString)
        assertEquals("question", request.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
    }
    @Test
    fun `format is opt in and accepted only for trusted qq source`() {
        for (source in listOf("qq", "web", "minecraft", null)) {
            val request = JsonParser.parseString("""{"qbot_reply_format":"messages_v1"}""").asJsonObject
            assertEquals(source == "qq", LLMBotReplyFormat.consume(request, source))
            assertFalse(request.has("qbot_reply_format"))
        }
        assertFalse(LLMBotReplyFormat.consume(JsonObject(), "qq"))
        assertFalse(LLMBotReplyFormat.consume(JsonParser.parseString("""{"qbot_reply_format":true}""").asJsonObject, "qq"))
    }

    @Test
    fun `one sentence and ordinary paragraphs stay a single message`() {
        assertEquals(listOf("嗯，知道啦。"), LLMBotReplyFormat.split("嗯，知道啦。"))
        assertEquals(listOf("第一段。\n\n第二段。"), LLMBotReplyFormat.split("第一段。\n\n第二段。"))
    }

    @Test
    fun `model boundaries produce bubbles without changing usage or stored content`() {
        val result = JsonParser.parseString(LLMBotReplyFormat.formatResponse(response("好呀\n${LLMBotReplyFormat.BREAK}\n周末去吗？"))).asJsonObject
        val message = result.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        assertEquals("好呀\n\n周末去吗？", message.get("content").asString)
        assertEquals(listOf("好呀", "周末去吗？"), message.getAsJsonArray("qq_messages").map { it.asString })
        assertEquals(20, result.getAsJsonObject("usage").get("completion_tokens").asInt)
        assertEquals("stop", result.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
    }

    @Test
    fun `empty boundaries are ignored and excess messages are folded without text loss`() {
        val marker = LLMBotReplyFormat.BREAK
        assertEquals(listOf("一", "二", "三\n\n四"), LLMBotReplyFormat.split("$marker\n一\n$marker\n二\n$marker\n三\n$marker\n四\n$marker"))
    }

    @Test
    fun `inline marker and fenced code are never treated as message boundaries`() {
        val marker = LLMBotReplyFormat.BREAK
        val content = "示例 $marker 不应拆开\n```text\n$marker\n```"
        assertEquals(listOf(content), LLMBotReplyFormat.split(content))
    }

    @Test
    fun `missing text and error responses are preserved`() {
        for (body in listOf("not json", """{"error":{"message":"failed"}}""", """{"choices":[{"message":{"content":null}}]}""")) {
            assertEquals(parseOrText(body), parseOrText(LLMBotReplyFormat.formatResponse(body)))
        }
    }

    @Test
    fun `style lets model choose a single reply and preserves complete deliverables`() {
        assertTrue(LLMBotReplyFormat.systemRules.contains("不要每次都拆分"))
        assertTrue(LLMBotReplyFormat.systemRules.contains("不要按标点机械拆句"))
        assertTrue(LLMBotReplyFormat.systemRules.contains("优先保持完整"))
    }

    @Test
    fun `horizontal rules and alternate break markers produce message bubbles`() {
        assertEquals(listOf("你好呀", "今天天气真好！"), LLMBotReplyFormat.split("你好呀\n---\n今天天气真好！"))
        assertEquals(listOf("你好呀", "今天天气真好！"), LLMBotReplyFormat.split("你好呀\n***\n今天天气真好！"))
        assertEquals(listOf("你好呀", "今天天气真好！"), LLMBotReplyFormat.split("你好呀\n___\n今天天气真好！"))
        assertEquals(listOf("你好呀", "今天天气真好！"), LLMBotReplyFormat.split("你好呀\n<<<BREAK>>>\n今天天气真好！"))
        assertEquals(listOf("你好呀", "今天天气真好！"), LLMBotReplyFormat.split("你好呀\n[BREAK]\n今天天气真好！"))
        assertEquals(listOf("你好呀", "今天天气真好！"), LLMBotReplyFormat.split("你好呀\n**<<<QBOT_MESSAGE_BREAK>>>**\n今天天气真好！"))
    }

    @Test
    fun `mergeIntermediateMessages combines intermediate tool text with final response`() {
        val finalBody = response("这是最终回答")
        val mergedWithBreak = mergeIntermediateMessages(finalBody, listOf("好呀，稍等我一下~"), useBreakMarker = true)
        val formatted = LLMBotReplyFormat.formatResponse(mergedWithBreak)
        val message = JsonParser.parseString(formatted).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        assertEquals("好呀，稍等我一下~\n\n这是最终回答", message.get("content").asString)
        assertEquals(listOf("好呀，稍等我一下~", "这是最终回答"), message.getAsJsonArray("qq_messages").map { it.asString })

        val mergedWithoutBreak = mergeIntermediateMessages(finalBody, listOf("好呀，稍等我一下~"), useBreakMarker = false)
        val messageNoBreak = JsonParser.parseString(mergedWithoutBreak).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        assertEquals("好呀，稍等我一下~\n\n这是最终回答", messageNoBreak.get("content").asString)
        assertFalse(messageNoBreak.has("qq_messages"))
    }

    private fun response(content: String): String = JsonObject().apply {
        add("choices", JsonParser.parseString("""[{"message":{"role":"assistant"},"finish_reason":"stop"}]"""))
        getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").addProperty("content", content)
        add("usage", JsonParser.parseString("""{"completion_tokens":20}"""))
    }.toString()

    private fun parseOrText(body: String): Any = runCatching { JsonParser.parseString(body) }.getOrElse { body }
}
