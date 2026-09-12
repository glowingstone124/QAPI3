package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMAnthropicAdapterTest {
    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun `converts system images tools and search without leaking chat-only fields`() {
        val chat = """{
            "model":"claude-test","user_id":"qq:123","web_search_options":{},"reasoning_effort":"high",
            "thinking":{"type":"enabled"},"stream_options":{"include_usage":true},
            "messages":[
                {"role":"system","content":"server instructions"},
                {"role":"user","content":[
                    {"type":"text","text":"look up this image"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,YWJj","detail":"high"}},
                    {"type":"image_url","image_url":{"url":"https://images.example/image.png"}}
                ]}
            ]
        }"""
        val tools = JsonParser.parseString("""[{"type":"function","function":{"name":"lookup","description":"find facts","parameters":{"type":"object","properties":{}}}}]""").asJsonArray
        val request = LLMAnthropicAdapter.fromChatRequest(chat, tools, LLMReasoningEffort.HIGH)
        assertEquals("server instructions", request.getAsJsonArray("system")[0].asJsonObject.get("text").asString)
        val content = request.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals("base64", content[1].asJsonObject.getAsJsonObject("source").get("type").asString)
        assertEquals("image/png", content[1].asJsonObject.getAsJsonObject("source").get("media_type").asString)
        assertEquals("url", content[2].asJsonObject.getAsJsonObject("source").get("type").asString)
        assertTrue(request.getAsJsonArray("tools")[0].asJsonObject.has("input_schema"))
        assertEquals("web_search_20250305", request.getAsJsonArray("tools")[1].asJsonObject.get("type").asString)
        assertEquals("auto", request.getAsJsonObject("tool_choice").get("type").asString)
        assertEquals(8192, request.get("max_tokens").asInt)
        assertEquals(4096, request.getAsJsonObject("thinking").get("budget_tokens").asInt)
        assertFalse(request.toString().contains("qq:123"))
        for (field in listOf("user_id", "reasoning_effort", "web_search_options", "stream_options")) assertFalse(request.has(field))
    }

    @Test fun `search remains enabled when local tools are disabled and summary can omit search`() {
        val chat = """{"model":"test","messages":[{"role":"user","content":"question"}]}"""
        val interactive = LLMAnthropicAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.NONE)
        assertEquals("web_search", interactive.getAsJsonArray("tools")[0].asJsonObject.get("name").asString)
        val summary = LLMAnthropicAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.NONE, webSearch = false)
        assertFalse(summary.has("tools"))
        assertFalse(summary.has("tool_choice"))
    }

    @Test fun `manual thinking validates budget and adaptive thinking maps effort`() {
        val chat = """{"model":"test","max_tokens":2048,"temperature":0.2,"messages":[{"role":"user","content":"question"}]}"""
        val manual = LLMAnthropicAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.MAX)
        assertEquals(2047, manual.getAsJsonObject("thinking").get("budget_tokens").asInt)
        assertFalse(manual.has("temperature"))
        val adaptive = LLMAnthropicAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.HIGH, thinkingMode = "adaptive")
        assertEquals("adaptive", adaptive.getAsJsonObject("thinking").get("type").asString)
        assertEquals("high", adaptive.getAsJsonObject("output_config").get("effort").asString)
        assertFalse(adaptive.getAsJsonObject("thinking").has("budget_tokens"))
        assertFailsWith<IllegalArgumentException> {
            LLMAnthropicAdapter.fromChatRequest(chat.replace("2048", "1024"), JsonArray(), LLMReasoningEffort.HIGH)
        }
    }

    @Test fun `tool continuation preserves native thinking signatures and server search content`() {
        val request = obj("""{"messages":[{"role":"user","content":"question"}]}""")
        val response = obj("""{"stop_reason":"tool_use","content":[
            {"type":"thinking","thinking":"original","signature":"signed"},
            {"type":"redacted_thinking","data":"opaque"},
            {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"news"}},
            {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[]},
            {"type":"tool_use","id":"local_1","name":"lookup","input":{"id":1}}
        ]}""")
        assertEquals(listOf("lookup"), LLMAnthropicAdapter.functionCalls(response).map { it.name })
        LLMAnthropicAdapter.appendContinuation(request, response, mapOf("local_1" to """{"error":"missing"}"""))
        val messages = request.getAsJsonArray("messages")
        assertEquals(response.get("content"), messages[1].asJsonObject.get("content"))
        val result = messages[2].asJsonObject.getAsJsonArray("content")[0].asJsonObject
        assertEquals("tool_result", result.get("type").asString)
        assertEquals("local_1", result.get("tool_use_id").asString)
        assertTrue(result.get("is_error").asBoolean)
    }

    @Test fun `converts final text citations cache counts and truncated finish reason`() {
        val response = obj("""{"id":"msg_1","model":"test","stop_reason":"max_tokens","content":[
            {"type":"thinking","thinking":"hidden","signature":"signed"},
            {"type":"text","text":"Verified fact.","citations":[{"type":"web_search_result_location","title":"Source","url":"https://source.example/fact"}]}
        ],"usage":{"input_tokens":5,"output_tokens":7,"cache_read_input_tokens":20,"cache_creation_input_tokens":10}}""")
        val completion = obj(LLMAnthropicAdapter.toChatCompletion(response))
        val choice = completion.getAsJsonArray("choices")[0].asJsonObject
        assertEquals("length", choice.get("finish_reason").asString)
        assertEquals("Verified fact. [Source](https://source.example/fact)", choice.getAsJsonObject("message").get("content").asString)
        assertEquals(35, completion.getAsJsonObject("usage").get("prompt_tokens").asInt)
        assertEquals(42, completion.getAsJsonObject("usage").get("total_tokens").asInt)
        assertEquals(20, completion.getAsJsonObject("usage").get("prompt_cache_hit_tokens").asInt)
        assertEquals(15, completion.getAsJsonObject("usage").get("prompt_cache_miss_tokens").asInt)
    }

    @Test fun `stream reassembles fragmented tool JSON signatures and cumulative usage`() {
        val state = LLMAnthropicStreamState()
        val events = listOf(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","model":"test","usage":{"input_tokens":5,"cache_read_input_tokens":10,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thought"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"nature"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"lookup","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"id\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"42}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
            """{"type":"message_stop"}""",
        )
        val updates = events.map { state.accept(obj(it)) }
        assertTrue(updates.all { it.text.isEmpty() })
        val completed = state.completed!!
        assertEquals("signature", completed.getAsJsonArray("content")[0].asJsonObject.get("signature").asString)
        assertEquals("{\"id\":42}", LLMAnthropicAdapter.functionCalls(completed).single().arguments)
        assertEquals(9, completed.getAsJsonObject("usage").get("output_tokens").asInt)
        assertEquals(10, completed.getAsJsonObject("usage").get("cache_read_input_tokens").asInt)
    }
}
