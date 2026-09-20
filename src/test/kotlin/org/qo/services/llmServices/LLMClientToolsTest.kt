package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.*

class LLMClientToolsTest {
    private fun obj(text: String) = JsonParser.parseString(text).asJsonObject
    private val tools = JsonParser.parseString("""[{"type":"function","function":{"name":"fill","parameters":{"type":"object","properties":{"revision":{"type":"integer"}},"required":["revision"]}}}]""").asJsonArray
    private fun chat() = obj("""{"model":"test","messages":[{"role":"user","content":"build"}],"max_tokens":2048,"tool_choice":"auto"}""")
    private val nativeCall = """{"id":"call_1","type":"function","function":{"name":"fill","arguments":"{\"revision\":0}"}}"""

    @Test fun `builder reserves enough output space for complete native tool calls`() {
        assertEquals(32768, clientToolOutputTokens(12000, 65536))
        assertEquals(4096, clientToolOutputTokens(28672, 32768))
        assertFailsWith<IllegalArgumentException> { clientToolOutputTokens(31000, 32768) }
        assertTrue(clientToolFinishError("length").contains("长度上限"))
    }

    @Test fun `parallel model calls are reduced to one browser edit step`() {
        val completion = obj("""{"choices":[{"message":{"role":"assistant","content":"all work completed","tool_calls":[$nativeCall,{"id":"call_2","type":"function","function":{"name":"fill","arguments":"{\"revision\":0}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":200}}""")
        val limited = limitClientToolStep(completion)
        val message = limited.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        assertEquals(1, message.getAsJsonArray("tool_calls").size())
        assertEquals("call_1", message.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        assertEquals("", message.get("content").asString)
        assertEquals(200, limited.getAsJsonObject("usage").get("completion_tokens").asInt)
    }

    @Test fun `commandcode builder steps have a small budget and explicit low reasoning`() {
        val step = chat().apply { addProperty("max_tokens", 32768) }
        assertEquals(LLMReasoningEffort.LOW,
            configureClientToolStep(step, LLMProtocol.COMMANDCODE, LLMReasoningEffort.HIGH))
        assertEquals(8192, step.get("max_tokens").asInt)
        val final = chat().apply { addProperty("tool_choice", "none"); addProperty("max_tokens", 32768) }
        assertEquals(LLMReasoningEffort.HIGH,
            configureClientToolStep(final, LLMProtocol.COMMANDCODE, LLMReasoningEffort.HIGH))
        assertEquals(32768, final.get("max_tokens").asInt)
    }

    @Test fun `commandcode builder request overrides the shared 120 second timeout`() = runBlocking {
        val engine = MockEngine {
            delay(100)
            respond("""{"type":"finish","finishReason":"end-turn","totalUsage":{"inputTokens":1,"outputTokens":1}}
""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) { install(HttpTimeout) { requestTimeoutMillis = 20 } }.use { client ->
            val body = LLMCommandCodeAdapter.fromChatRequest(chat(), tools, LLMReasoningEffort.LOW)
            val (status, response) = runCommandCodeUpstream(client, "https://model.example/api", "token", body,
                requestTimeoutMillis = 1000)
            assertEquals(200, status)
            assertEquals("stop", obj(response).getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
        }
    }

    @Test fun `commandcode truncated step retries with a bounded larger output`() = runBlocking {
        val outgoing = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            outgoing += obj((request.body as TextContent).text)
            val response = if (outgoing.size == 1)
                """{"type":"finish","finishReason":"max-tokens","totalUsage":{"inputTokens":30,"outputTokens":8192}}
"""
            else """{"type":"tool-call","toolCallId":"call_1","toolName":"fill","input":{"revision":0}}
{"type":"finish","finishReason":"tool-calls","totalUsage":{"inputTokens":35,"outputTokens":20}}
"""
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val services = org.mockito.Mockito.mock(LLMServices::class.java)
        HttpClient(engine).use { client ->
            val step = clientToolStepChat(chat().apply { addProperty("max_tokens", 32768) })
            val effort = configureClientToolStep(step, LLMProtocol.COMMANDCODE, LLMReasoningEffort.HIGH)
            val (status, body) = services.runClientToolAgentTurn(client, "https://model.example/api", "token",
                LLMProtocol.COMMANDCODE, step, tools, effort, 524288)
            assertEquals(200, status)
            assertEquals(listOf(8192, 32768), outgoing.map { it.getAsJsonObject("params").get("max_tokens").asInt })
            assertTrue(outgoing.all { it.getAsJsonObject("params").get("reasoning_effort").asString == "low" })
            assertEquals("tool_calls", obj(body).getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
            assertEquals(2, obj(body).getAsJsonObject("usage").get("qapi_api_calls").asInt)
        }
    }

    @Test fun `old builder turns are compacted while current tool results stay paired`() {
        val messages = JsonArray().apply {
            add(obj("""{"role":"system","content":"instructions"}"""))
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", "old request ".repeat(7000)) })
            add(obj("""{"role":"assistant","content":"old answer"}"""))
            add(obj("""{"role":"user","content":"recent request"}"""))
            add(obj("""{"role":"assistant","content":"recent answer"}"""))
            add(obj("""{"role":"user","content":"[Kotshi Builder 工作区上下文] current snapshot"}"""))
            add(obj("""{"role":"user","content":"add a roof"}"""))
            add(obj("""{"role":"assistant","content":"","tool_calls":[$nativeCall]}"""))
            add(obj("""{"role":"tool","tool_call_id":"call_1","content":"{\"ok\":true}"}"""))
            add(obj("""{"role":"user","content":"[Builder 执行进度] continue"}"""))
        }
        val services = org.mockito.Mockito.mock(LLMServices::class.java)
        val compacted = services.compactClientToolMessages(messages, tools, 65536)
        val text = compacted.toString()
        assertFalse(text.contains("old request"))
        assertTrue(text.contains("recent request"))
        assertTrue(text.contains("current snapshot"))
        assertTrue(text.contains("call_1"))
        validateClientToolHistory(compacted)
    }

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
                if (protocol == LLMProtocol.RESPONSES) assertEquals(false,outgoing.get("parallel_tool_calls").asBoolean)
                if (protocol == LLMProtocol.ANTHROPIC) assertEquals(true,outgoing.getAsJsonObject("tool_choice").get("disable_parallel_tool_use").asBoolean)
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

    @Test fun `truncated builder step retries once without executing partial calls and counts both usages`() = runBlocking {
        val outgoing = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            outgoing += obj((request.body as TextContent).text)
            val response = if (outgoing.size == 1)
                """{"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}],"usage":{"prompt_tokens":100,"completion_tokens":32768}}"""
            else """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[$nativeCall]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":110,"completion_tokens":20}}"""
            respond(response,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }
        val services = org.mockito.Mockito.mock(LLMServices::class.java)
        HttpClient(engine).use { client ->
            val chat = clientToolStepChat(chat().apply { addProperty("tool_choice","auto"); addProperty("max_tokens",32768) })
            val (status,body) = services.runClientToolAgentTurn(client,"https://model.example/api","token",
                LLMProtocol.CHAT_COMPLETIONS,chat,tools,LLMReasoningEffort.NONE,524288)
            assertEquals(200,status)
            assertEquals(2,outgoing.size)
            assertEquals(131072,outgoing[1].get("max_tokens").asInt)
            assertTrue(outgoing[1].toString().contains("上一尝试达到长度上限"))
            assertEquals("tool_calls",obj(body).getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
            assertEquals(32788,obj(body).getAsJsonObject("usage").get("completion_tokens").asInt)
            assertEquals(2,obj(body).getAsJsonObject("usage").get("qapi_api_calls").asInt)
        }
    }

    @Test fun `anthropic and responses truncation enter the same safe retry loop`() = runBlocking {
        for (protocol in listOf(LLMProtocol.ANTHROPIC, LLMProtocol.RESPONSES)) {
            var requests = 0
            val engine = MockEngine {
                requests++
                val response = when (protocol) {
                    LLMProtocol.ANTHROPIC -> if (requests == 1)
                        """{"type":"message","stop_reason":"max_tokens","content":[{"type":"tool_use","id":"partial","name":"fill","input":{"revision":0}}],"usage":{"input_tokens":4,"output_tokens":32768}}"""
                    else """{"type":"message","stop_reason":"tool_use","content":[{"type":"tool_use","id":"call_1","name":"fill","input":{"revision":0}}],"usage":{"input_tokens":5,"output_tokens":10}}"""
                    else -> if (requests == 1)
                        """{"status":"incomplete","error":null,"output":[{"type":"function_call","call_id":"partial","name":"fill","arguments":"{\"revision\":"}],"usage":{"input_tokens":4,"output_tokens":32768}}"""
                    else """{"status":"completed","error":null,"output":[{"type":"function_call","call_id":"call_1","name":"fill","arguments":"{\"revision\":0}"}],"usage":{"input_tokens":5,"output_tokens":10}}"""
                }
                respond(response,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val services = org.mockito.Mockito.mock(LLMServices::class.java)
            HttpClient(engine).use { client ->
                val (status,body) = services.runClientToolAgentTurn(client,"https://model.example/api","token",protocol,
                    clientToolStepChat(chat().apply { addProperty("tool_choice","auto"); addProperty("max_tokens",32768) }),
                    tools,LLMReasoningEffort.NONE,524288)
                assertEquals(200,status)
                assertEquals(2,requests)
                assertEquals("tool_calls",obj(body).getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
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
