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
import kotlin.test.*

class LLMClientToolsTest {
    private fun obj(text: String) = JsonParser.parseString(text).asJsonObject
    private val tools = JsonParser.parseString("""[{"type":"function","function":{"name":"fill","parameters":{"type":"object","properties":{"revision":{"type":"integer"}},"required":["revision"]}}}]""").asJsonArray
    private fun chat() = obj("""{"model":"test","messages":[{"role":"user","content":"build"}],"max_tokens":2048,"tool_choice":"auto"}""")
    private val nativeCall = """{"id":"call_1","type":"function","function":{"name":"fill","arguments":"{\"revision\":0}"}}"""

    @Test fun `client tools are explicitly web only and restricted to builder names`() {
        fun request() = chat().apply { addProperty("tool_execution","client");add("tools",tools.deepCopy()) }
        assertEquals(1, extractClientTools(request(),"web")!!.size())
        assertFails { extractClientTools(request(),"qq") }
        assertFails { extractClientTools(request().apply { getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").addProperty("name","run_shell") },"web") }
        assertEquals(null,extractClientTools(chat(),"web"))
    }

    @Test fun `native history keeps paired IDs and refuses incomplete or repeated results`() {
        val history=JsonParser.parseString("""[{"role":"assistant","content":"","tool_calls":[$nativeCall]},{"role":"tool","tool_call_id":"call_1","content":"{\"ok\":true}"},{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}]""").asJsonArray
        validateClientToolHistory(history)
        assertFails { validateClientToolHistory(JsonArray().apply { add(history[0]) }) }
        assertFails { validateClientToolHistory(JsonArray().apply { add(history[1]) }) }
        assertFails { validateClientToolHistory(history.deepCopy().apply { add(history[1]) }) }
        val responses=LLMResponsesAdapter.fromChatRequest(chat().apply { add("messages",history) }.toString(),tools,LLMReasoningEffort.NONE,webSearch=false)
        val input=responses.getAsJsonArray("input")
        assertEquals("function_call",input[0].asJsonObject.get("type").asString)
        assertEquals("function_call_output",input[1].asJsonObject.get("type").asString)
        assertEquals("call_1",input[1].asJsonObject.get("call_id").asString)
        assertEquals("input_image",input[2].asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
        val conversation=JsonArray().apply {
            add(obj("""{"role":"user","content":"original building request"}"""))
            history.forEach(::add)
            add(obj("""{"role":"user","content":"remaining tool budget: 7"}"""))
        }
        assertEquals("original building request",clientOriginalUserMessage(conversation)!!.get("content").asString)
    }

    @Test fun `all four protocols return one native call without executing or looping on the server`() = runBlocking {
        for (protocol in LLMProtocol.entries) {
            var requests=0
            val engine=MockEngine { request ->
                requests++
                val outgoing=obj((request.body as TextContent).text)
                val definitions=if(protocol==LLMProtocol.COMMANDCODE) outgoing.getAsJsonObject("params").getAsJsonArray("tools") else outgoing.getAsJsonArray("tools")
                assertEquals(1,definitions.size())
                assertFalse(outgoing.toString().contains("web_search"))
                val response=when(protocol) {
                    LLMProtocol.CHAT_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[$nativeCall]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":2,"completion_tokens":3}}"""
                    LLMProtocol.RESPONSES -> """{"status":"completed","output":[{"type":"function_call","call_id":"call_1","name":"fill","arguments":"{\"revision\":0}"}],"usage":{"input_tokens":2,"output_tokens":3}}"""
                    LLMProtocol.ANTHROPIC -> """{"type":"message","stop_reason":"tool_use","content":[{"type":"tool_use","id":"call_1","name":"fill","input":{"revision":0}}],"usage":{"input_tokens":2,"output_tokens":3}}"""
                    LLMProtocol.COMMANDCODE -> """{"type":"tool-call","toolCallId":"call_1","toolName":"fill","input":{"revision":0}}
{"type":"finish","finishReason":"tool-calls","totalUsage":{"inputTokens":2,"outputTokens":3}}
"""
                }
                respond(response,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
            HttpClient(engine).use { client ->
                val (status,body)=runClientToolUpstream(client,"https://model.example/api","token",protocol,chat(),tools,LLMReasoningEffort.NONE)
                assertEquals(200,status);assertEquals(1,requests)
                val choice=obj(body).getAsJsonArray("choices")[0].asJsonObject
                assertEquals("tool_calls",choice.get("finish_reason").asString)
                val call=choice.getAsJsonObject("message").getAsJsonArray("tool_calls")[0].asJsonObject
                assertEquals("call_1",call.get("id").asString)
                assertEquals("fill",call.getAsJsonObject("function").get("name").asString)
            }
        }
    }

    @Test fun `final anthropic turn retains schemas but disables further tool selection`() = runBlocking {
        val engine=MockEngine { request ->
            val body=obj((request.body as TextContent).text)
            assertEquals("none",body.getAsJsonObject("tool_choice").get("type").asString)
            assertEquals(1,body.getAsJsonArray("tools").size())
            assertEquals("disabled",body.getAsJsonObject("thinking").get("type").asString)
            respond("""{"type":"message","stop_reason":"end_turn","content":[{"type":"text","text":"done"}],"usage":{"input_tokens":2,"output_tokens":3}}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }
        HttpClient(engine).use { client ->
            assertEquals(200,runClientToolUpstream(client,"https://model.example/api","token",LLMProtocol.ANTHROPIC,chat().apply { addProperty("tool_choice","none") },tools,LLMReasoningEffort.HIGH).first)
        }
    }
}
