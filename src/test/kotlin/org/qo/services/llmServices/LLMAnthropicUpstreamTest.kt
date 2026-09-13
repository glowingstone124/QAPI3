package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMAnthropicUpstreamTest {
    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject
    private val finalMessage = """{"id":"msg_final","type":"message","model":"test","stop_reason":"end_turn","content":[
        {"type":"text","text":"Verified answer.","citations":[{"type":"web_search_result_location","title":"Source","url":"https://source.example/fact"}]}
    ],"usage":{"input_tokens":11,"output_tokens":13,"cache_read_input_tokens":17,"cache_creation_input_tokens":19}}"""
    private val pausedMessage = """{"id":"msg_pause","type":"message","model":"test","stop_reason":"pause_turn","content":[
        {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"current fact"}},
        {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[]}
    ],"usage":{"input_tokens":2,"output_tokens":3}}"""
    private val toolMessage = """{"id":"msg_tool","type":"message","model":"test","stop_reason":"tool_use","content":[
        {"type":"thinking","thinking":"native thought","signature":"signed"},
        {"type":"tool_use","id":"call_1","name":"lookup","input":{"id":42}}
    ],"usage":{"input_tokens":5,"output_tokens":7}}"""

    private fun request(stream: Boolean = false): JsonObject = LLMAnthropicAdapter.fromChatRequest(
        """{"model":"test","messages":[{"role":"user","content":"look up current facts"}]}""",
        JsonParser.parseString("""[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{"id":{"type":"integer"}}}}}]""").asJsonArray,
        LLMReasoningEffort.NONE,
        stream = stream,
    )

    @Test fun `nonstream search pause and local tool rounds retain native blocks and aggregate usage`() = runBlocking {
        runSearchAndToolRounds(stream = false)
    }

    @Test fun `a failed later tool round preserves the cost of earlier calls`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            if (++calls == 1) respond(toolMessage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("""{"error":{"message":"unavailable"}}""", HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { client ->
            val result = runAnthropicUpstream(client, "https://gateway.example/messages", "test", request(), 1,
                executeTool = { "result" })
            assertEquals(503, result.first)
            val usage = obj(result.second).getAsJsonObject("usage")
            assertEquals(5, usage.get("prompt_tokens").asInt)
            assertEquals(7, usage.get("completion_tokens").asInt)
            assertEquals(1, usage.get("qapi_api_calls").asInt)
        }
    }

    @Test fun `an omitted tool round usage is marked incomplete`() = runBlocking {
        var calls = 0
        val first = obj(toolMessage).apply { remove("usage") }.toString()
        val engine = MockEngine {
            respond(if (++calls == 1) first else finalMessage, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { client ->
            val result = runAnthropicUpstream(client, "https://gateway.example/messages", "test", request(), 1,
                executeTool = { "result" })
            assertEquals(200, result.first)
            assertFalse(obj(result.second).getAsJsonObject("usage").get("qapi_usage_complete").asBoolean)
        }
    }

    @Test fun `stream search pause and local tool rounds emit citations and aggregate usage`() = runBlocking {
        runSearchAndToolRounds(stream = true)
    }

    private suspend fun runSearchAndToolRounds(stream: Boolean) {
        val sent = mutableListOf<JsonObject>()
        val updates = mutableListOf<AnthropicStreamUpdate>()
        val engine = MockEngine { httpRequest ->
            assertEquals("https://gateway.example/messages", httpRequest.url.toString())
            assertEquals("test-token", httpRequest.headers["x-api-key"])
            assertEquals("2023-06-01", httpRequest.headers["anthropic-version"])
            assertEquals(null, httpRequest.headers[HttpHeaders.Authorization])
            val body = obj((httpRequest.body as TextContent).text)
            sent.add(body)
            assertTrue(body.getAsJsonArray("tools").any { it.asJsonObject.get("name").asString == "web_search" })
            val message = listOf(pausedMessage, toolMessage, finalMessage)[sent.lastIndex]
            respond(if (stream) sse(obj(message)) else message, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, if (stream) "text/event-stream" else "application/json"))
        }
        var accepted = 0
        val executed = mutableListOf<ResponseFunctionCall>()
        HttpClient(engine).use { client ->
            val (status, completion) = runAnthropicUpstream(
                client, "https://gateway.example/messages", "test-token", request(stream), 1,
                executeTool = { call -> executed.add(call); """{"value":"current fact"}""" },
                onUpdate = { updates.add(it) },
                onAccepted = { accepted++ },
            )
            assertEquals(200, status)
            assertEquals(3, accepted)
            assertEquals(listOf("lookup"), executed.map { it.name })
            assertEquals("{\"id\":42}", executed.single().arguments)
            val answer = obj(completion)
            assertEquals("Verified answer. [Source](https://source.example/fact)",
                answer.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString)
            val usage = answer.getAsJsonObject("usage")
            assertEquals(54, usage.get("prompt_tokens").asInt)
            assertEquals(23, usage.get("completion_tokens").asInt)
            assertEquals(77, usage.get("total_tokens").asInt)
            assertEquals(17, usage.get("prompt_cache_hit_tokens").asInt)
            val continuation = sent[1].getAsJsonArray("messages")[1].asJsonObject
            assertEquals(obj(pausedMessage).get("content"), continuation.get("content"))
            val finalMessages = sent[2].getAsJsonArray("messages")
            assertEquals(obj(toolMessage).get("content"), finalMessages[2].asJsonObject.get("content"))
            assertEquals("call_1", finalMessages[3].asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("tool_use_id").asString)
            if (stream) {
                assertTrue(updates.any { it.phase == "web_search" })
                assertTrue(updates.any { it.phase == "thinking" })
                assertEquals("Verified answer. [Source](https://source.example/fact)", updates.joinToString("") { it.text })
                assertFalse(updates.joinToString("") { it.text }.contains("native thought"))
            }
        }
    }

    @Test fun `HTTP success with search failure returns an error instead of a successful answer`() = runBlocking {
        val message = """{"type":"message","stop_reason":"end_turn","content":[
            {"type":"web_search_tool_result","tool_use_id":"srv_1","content":{"type":"web_search_tool_result_error","error_code":"unavailable"}},
            {"type":"text","text":"unverified answer"}
        ]}"""
        val engine = MockEngine { respond(message, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        HttpClient(engine).use { client ->
            val result = runAnthropicUpstream(client, "https://gateway.example/messages", "test", request(), 1, { error("must not execute") })
            assertEquals(502, result.first)
            assertEquals("web_search_error", obj(result.second).getAsJsonObject("error").get("code").asString)
        }
    }

    @Test fun `stream error and premature EOF fail without executing partial tools`() = runBlocking {
        for (body in listOf(
            "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}\n\n",
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"usage\":{}}}\n\n",
            sse(obj(toolMessage)).replace("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n", ""),
        )) {
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
            HttpClient(engine).use { client ->
                assertFailsWith<IllegalStateException> {
                    runAnthropicUpstream(client, "https://gateway.example/messages", "test", request(true), 1, { error("must not execute") })
                }
            }
        }
    }

    @Test fun `upstream HTTP errors retain status and are not accepted`() = runBlocking {
        val engine = MockEngine { respond("""{"error":{"type":"rate_limit_error","message":"slow down"}}""", HttpStatusCode.TooManyRequests) }
        var accepted = false
        HttpClient(engine).use { client ->
            val result = runAnthropicUpstream(client, "https://gateway.example/messages", "test", request(), 1,
                executeTool = { error("must not execute") }, onAccepted = { accepted = true })
            assertEquals(429, result.first)
            assertFalse(accepted)
        }
    }

    @Test fun `stream request tolerates a nonstream upstream response`() = runBlocking {
        val updates = mutableListOf<AnthropicStreamUpdate>()
        val engine = MockEngine { respond(finalMessage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        HttpClient(engine).use { client ->
            val result = runAnthropicUpstream(client, "https://gateway.example/messages", "test", request(true), 1,
                executeTool = { error("must not execute") }, onUpdate = { updates.add(it) })
            assertEquals(200, result.first)
            assertEquals("Verified answer. [Source](https://source.example/fact)", updates.joinToString("") { it.text })
        }
    }

    private fun sse(message: JsonObject): String = buildString {
        fun event(value: JsonObject) {
            append("event: ").append(value.get("type").asString).append('\n')
            append("data: ").append(value).append("\n\n")
        }
        val start = message.deepCopy()
        start.add("content", JsonArray())
        start.remove("stop_reason")
        event(JsonObject().apply { addProperty("type", "message_start"); add("message", start) })
        message.getAsJsonArray("content").forEachIndexed { index, item ->
            val block = item.asJsonObject
            val initial = block.deepCopy()
            val type = block.get("type").asString
            if (type == "text") { initial.addProperty("text", ""); initial.remove("citations") }
            if (type == "thinking") { initial.addProperty("thinking", ""); initial.addProperty("signature", "") }
            if (type == "tool_use" || type == "server_tool_use") initial.add("input", JsonObject())
            event(JsonObject().apply { addProperty("type", "content_block_start"); addProperty("index", index); add("content_block", initial) })
            fun delta(type: String, key: String, value: com.google.gson.JsonElement) {
                event(JsonObject().apply {
                    addProperty("type", "content_block_delta"); addProperty("index", index)
                    add("delta", JsonObject().apply { addProperty("type", type); add(key, value) })
                })
            }
            when (type) {
                "text" -> {
                    delta("text_delta", "text", block.get("text"))
                    block.getAsJsonArray("citations")?.forEach { delta("citations_delta", "citation", it) }
                }
                "thinking" -> {
                    delta("thinking_delta", "thinking", block.get("thinking"))
                    delta("signature_delta", "signature", block.get("signature"))
                }
                "tool_use", "server_tool_use" -> {
                    val input = block.get("input").toString()
                    input.chunked(3).forEach { delta("input_json_delta", "partial_json", com.google.gson.JsonPrimitive(it)) }
                }
            }
            event(JsonObject().apply { addProperty("type", "content_block_stop"); addProperty("index", index) })
        }
        event(JsonObject().apply {
            addProperty("type", "message_delta")
            add("delta", JsonObject().apply { add("stop_reason", message.get("stop_reason")) })
            add("usage", message.get("usage"))
        })
        event(JsonObject().apply { addProperty("type", "message_stop") })
    }
}
