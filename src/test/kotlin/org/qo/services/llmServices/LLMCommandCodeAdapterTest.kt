package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LLMCommandCodeAdapterTest {
    @Test fun `image data URL follows official CLI wire and preserves text order`() {
        val chat = JsonParser.parseString("""{
            "model":"deepseek/deepseek-v4.1-flash",
            "messages":[{"role":"system","content":"Be brief"},{"role":"user","content":[
                {"type":"text","text":"What color?"},
                {"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}
            ]}]
        }""").asJsonObject
        val wire = LLMCommandCodeAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.NONE)
        val params = wire.getAsJsonObject("params")
        assertEquals("Be brief", params.get("system").asString)
        assertTrue(params.get("stream").asBoolean)
        val parts = params.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals("What color?", parts[0].asJsonObject.get("text").asString)
        val image = parts[1].asJsonObject
        assertEquals("image", image.get("type").asString)
        assertEquals("data:image/png;base64,AQID", image.get("image").asString)
        assertEquals("image/png", image.get("mimeType").asString)
        assertTrue(!image.has("source"))
    }

    @Test fun `remote image is rejected instead of silently omitted`() {
        val chat = JsonParser.parseString("""{"model":"vision","messages":[{"role":"user","content":[
            {"type":"image_url","image_url":{"url":"https://example.com/image.png"}}
        ]}]}""").asJsonObject
        assertFailsWith<IllegalArgumentException> {
            LLMCommandCodeAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.NONE)
        }
    }

    @Test fun `fallback only retries a first round server failure before text is emitted`() {
        assertTrue(shouldFallbackCommandCode(503, "upstream unavailable", 0, false))
        assertTrue(shouldFallbackCommandCode(502, "stream ended before finish", 0, false))
        assertTrue(!shouldFallbackCommandCode(401, "invalid key", 0, false))
        assertTrue(!shouldFallbackCommandCode(502, "tool_round_limit", 0, false))
        assertTrue(!shouldFallbackCommandCode(503, "upstream unavailable", 1, false))
        assertTrue(!shouldFallbackCommandCode(503, "upstream unavailable", 0, true))
    }

    @Test fun `jsonl response becomes chat completion with tool calls and real usage`() = runBlocking {
        val chat = JsonParser.parseString("""{"model":"moonshotai/Kimi-K3","messages":[{"role":"user","content":"hi"}]}""").asJsonObject
        val body = LLMCommandCodeAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.NONE)
        val engine = MockEngine { request ->
            assertEquals("Bearer key", request.headers[HttpHeaders.Authorization])
            assertEquals("cli", request.headers[HttpHeaders.UserAgent])
            assertTrue((request.body as TextContent).text.contains("moonshotai/Kimi-K3"))
            respond("""{"type":"start"}
{"type":"reasoning-delta","text":"thinking"}
{"type":"text-delta","text":"hello"}
{"type":"tool-call","toolCallId":"call_1","toolName":"clock","input":{"zone":"UTC"}}
{"type":"finish","finishReason":"tool-calls","totalUsage":{"inputTokens":10,"outputTokens":4,"inputTokenDetails":{"cacheReadTokens":2}}}
""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
        }
        HttpClient(engine).use { client ->
            val (status, result) = runCommandCodeUpstream(client, "https://example.com/alpha/generate", "key", body)
            assertEquals(200, status)
            val root = JsonParser.parseString(result).asJsonObject
            val choice = root.getAsJsonArray("choices")[0].asJsonObject
            assertEquals("tool_calls", choice.get("finish_reason").asString)
            val message = choice.getAsJsonObject("message")
            assertEquals("hello", message.get("content").asString)
            assertEquals("thinking", message.get("reasoning_content").asString)
            assertEquals("clock", message.getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
            assertEquals(10, root.getAsJsonObject("usage").get("prompt_tokens").asInt)
            assertEquals(2, root.getAsJsonObject("usage").get("prompt_cache_hit_tokens").asInt)
        }
    }
}
