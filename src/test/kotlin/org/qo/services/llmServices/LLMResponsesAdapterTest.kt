package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMResponsesAdapterTest {
    @Test
    fun `converts chat request and enables server web search`() {
        val functionTools = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", "get_server_status")
                    addProperty("description", "status")
                    add("parameters", JsonObject().apply { addProperty("type", "object") })
                })
            })
        }
        val request = LLMResponsesAdapter.fromChatRequest(
            """{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"today's news"}],"user_id":"qq:1"}""",
            functionTools,
            enableWebSearch = true,
            reasoningEffort = LLMReasoningEffort.NONE,
        )

        assertFalse(request.has("messages"))
        assertEquals("today's news", request.getAsJsonArray("input")[0].asJsonObject.get("content").asString)
        assertEquals("qq_1", request.get("user").asString)
        assertEquals("function", request.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
        assertEquals("get_server_status", request.getAsJsonArray("tools")[0].asJsonObject.get("name").asString)
        assertEquals("web_search", request.getAsJsonArray("tools")[1].asJsonObject.get("type").asString)
        assertEquals("none", request.getAsJsonObject("reasoning").get("effort").asString)
    }

    @Test
    fun `converts chat multimodal content to responses content`() {
        val request = LLMResponsesAdapter.fromChatRequest(
            """
            {
              "model":"deepseek-v4-flash",
              "messages":[
                {
                  "role":"user",
                  "content":[
                    {"type":"text","text":"这是什么？"},
                    {
                      "type":"image_url",
                      "image_url":{
                        "url":"data:image/png;base64,AQID",
                        "detail":"high"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
            JsonArray(),
            enableWebSearch = false,
            reasoningEffort = LLMReasoningEffort.MAX,
            stream = true,
        )

        val content = request.getAsJsonArray("input")[0]
            .asJsonObject
            .getAsJsonArray("content")

        assertEquals("input_text", content[0].asJsonObject.get("type").asString)
        assertEquals("这是什么？", content[0].asJsonObject.get("text").asString)
        assertEquals("input_image", content[1].asJsonObject.get("type").asString)
        assertEquals("data:image/png;base64,AQID", content[1].asJsonObject.get("image_url").asString)
        assertEquals("high", content[1].asJsonObject.get("detail").asString)
        assertEquals("max", request.getAsJsonObject("reasoning").get("effort").asString)
        assertTrue(request.get("stream").asBoolean)
    }

    @Test
    fun `converts responses result back to chat completion without web citations`() {
        val result = LLMResponsesAdapter.toChatCompletion(
            """{"id":"resp-1","model":"deepseek-v4-flash","status":"completed","output":[{"type":"web_search_call","id":"ws-1","status":"completed"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"search result (Example https://example.com)","annotations":[{"type":"url_citation","start_index":14,"end_index":43,"title":"Example","url":"https://example.com"}]}]}],"usage":{"input_tokens":10,"output_tokens":5}}"""
        )
        val json = JsonParser.parseString(result).asJsonObject

        val content = json.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString
        assertEquals("search result", content)
        assertFalse(content.contains("来源："))
        assertFalse(content.contains("https://example.com"))
        assertEquals(15, json.getAsJsonObject("usage").get("total_tokens").asInt)
    }

    @Test
    fun `preserves prompt cache usage from responses result`() {
        val result = LLMResponsesAdapter.toChatCompletion(
            """{"id":"resp-1","model":"deepseek-v4-flash","status":"completed","output":[],"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":80},"output_tokens":5}}"""
        )
        val usage = JsonParser.parseString(result).asJsonObject.getAsJsonObject("usage")

        assertEquals(80, usage.get("prompt_cache_hit_tokens").asInt)
        assertEquals(20, usage.get("prompt_cache_miss_tokens").asInt)
        assertEquals(80, usage.getAsJsonObject("prompt_tokens_details").get("cached_tokens").asInt)
    }

    @Test
    fun `continues local function calls without previous response id`() {
        val request = JsonObject().apply { add("input", JsonArray()) }
        val response = """{"output":[{"type":"function_call","call_id":"call-1","name":"get_server_status","arguments":"{}"}]}"""
        val calls = LLMResponsesAdapter.functionCalls(response)
        LLMResponsesAdapter.appendToolOutputs(request, response, mapOf("call-1" to "{\"online\":1}"))

        assertEquals("get_server_status", calls.single().name)
        assertEquals("function_call", request.getAsJsonArray("input")[0].asJsonObject.get("type").asString)
        assertEquals("function_call_output", request.getAsJsonArray("input")[1].asJsonObject.get("type").asString)
        assertTrue(request.getAsJsonArray("input")[1].asJsonObject.get("output").asString.contains("online"))
    }
}
