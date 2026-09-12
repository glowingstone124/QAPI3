package org.qo.services.llmServices

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMSummaryUpstreamTest {
    @Test fun `summaries use configured protocol endpoint auth and normalize the response`() = runBlocking {
        val chat = """{"model":"summary-test","stream":false,"max_tokens":1200,"thinking":{"type":"disabled"},"messages":[
            {"role":"system","content":"Summarize facts"},{"role":"user","content":"conversation"}
        ]}"""
        for (protocol in LLMProtocol.entries) {
            val endpoint = "https://summary.example/${protocol.wireValue}"
            val config = LLMSummaryConfig("summary", endpoint, "summary-token", "summary-test", 8192, protocol, "enabled")
            val engine = MockEngine { httpRequest ->
                assertEquals(endpoint, httpRequest.url.toString())
                val body = JsonParser.parseString((httpRequest.body as TextContent).text).asJsonObject
                assertFalse(body.has("tools"))
                assertFalse(body.has("web_search_options"))
                when (protocol) {
                    LLMProtocol.CHAT_COMPLETIONS -> {
                        assertTrue(body.has("messages"))
                        assertEquals("Bearer summary-token", httpRequest.headers[HttpHeaders.Authorization])
                        respond("""{"choices":[{"message":{"content":"summary"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    LLMProtocol.RESPONSES -> {
                        assertTrue(body.has("input"))
                        assertEquals(1200, body.get("max_output_tokens").asInt)
                        assertEquals("Bearer summary-token", httpRequest.headers[HttpHeaders.Authorization])
                        respond("""{"id":"resp_1","model":"summary-test","status":"completed","output":[
                            {"type":"message","content":[{"type":"output_text","text":"summary"}]}
                        ],"usage":{"input_tokens":3,"output_tokens":2}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    LLMProtocol.ANTHROPIC -> {
                        assertTrue(body.has("system"))
                        assertEquals(1200, body.get("max_tokens").asInt)
                        assertEquals("summary-token", httpRequest.headers["x-api-key"])
                        assertEquals("2023-06-01", httpRequest.headers["anthropic-version"])
                        respond("""{"id":"msg_1","type":"message","model":"summary-test","stop_reason":"end_turn",
                            "content":[{"type":"text","text":"summary"}],"usage":{"input_tokens":3,"output_tokens":2}}""",
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            }
            HttpClient(engine).use { client ->
                val (status, response) = runSummaryUpstream(client, chat, config)
                assertEquals(200, status)
                val content = JsonParser.parseString(response).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message").get("content").asString
                assertEquals("summary", content)
            }
        }
    }
}
