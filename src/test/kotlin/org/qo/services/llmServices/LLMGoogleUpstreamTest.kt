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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LLMGoogleUpstreamTest {
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val toolResponse = """{
        "candidates":[{
            "content":{
                "role":"model",
                "parts":[
                    {"thought":true,"text":"Let me look that up","thoughtSignature":"sig_turn_1"},
                    {"functionCall":{"id":"call_1","name":"lookup","args":{"id":42}},"thoughtSignature":"sig_turn_1"}
                ]
            },
            "finishReason":"STOP"
        }],
        "usageMetadata":{
            "promptTokenCount":10,
            "candidatesTokenCount":5,
            "thoughtsTokenCount":7,
            "totalTokenCount":22
        }
    }"""

    private val finalResponse = """{
        "candidates":[{
            "content":{
                "role":"model",
                "parts":[
                    {"text":"Fact verified from tool output."}
                ]
            },
            "finishReason":"STOP"
        }],
        "usageMetadata":{
            "promptTokenCount":25,
            "candidatesTokenCount":12,
            "thoughtsTokenCount":0,
            "totalTokenCount":37
        }
    }"""

    private fun sampleRequest(): JsonObject = LLMGoogleAdapter.fromChatRequest(
        """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"lookup fact"}]}""",
        JsonParser.parseString("""[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{"id":{"type":"integer"}}}}}]""").asJsonArray,
        LLMReasoningEffort.NONE
    )

    private fun sseData(json: String): String = "data: " + json.replace("\n", "").trim() + "\n\n"

    @Test
    fun `buildGoogleEndpoint builds correct URLs for base, slash models, placeholder, and stream`() {
        // Base URL without /models
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            buildGoogleEndpoint("https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash", stream = false)
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse",
            buildGoogleEndpoint("https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash", stream = true)
        )

        // Base URL with trailing slash or /models
        assertEquals(
            "https://proxy.example/v1beta/models/gemini-3.8-flash:generateContent",
            buildGoogleEndpoint("https://proxy.example/v1beta/models", "gemini-3.8-flash", stream = false)
        )
        assertEquals(
            "https://proxy.example/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse",
            buildGoogleEndpoint("https://proxy.example/v1beta/models/", "gemini-3.8-flash", stream = true)
        )

        // URL with {model} placeholder
        assertEquals(
            "https://custom.example/models/gemini-3.8-flash:generateContent",
            buildGoogleEndpoint("https://custom.example/models/{model}", "gemini-3.8-flash", stream = false)
        )

        // Full URL with :generateContent
        assertEquals(
            "https://full.example/models/my-model:streamGenerateContent?alt=sse",
            buildGoogleEndpoint("https://full.example/models/my-model:generateContent", "ignored", stream = true)
        )
    }

    @Test
    fun `nonstream chat with local tool round executes tool, preserves thoughtSignature, and aggregates usage`() = runBlocking {
        var calls = 0
        val sentRequests = mutableListOf<JsonObject>()
        val engine = MockEngine { httpRequest ->
            calls++
            val url = httpRequest.url.toString()
            assertTrue(url.endsWith(":generateContent"), "URL must end with :generateContent, got $url")
            assertEquals("test-token", httpRequest.headers["x-goog-api-key"])
            assertNull(httpRequest.headers[HttpHeaders.Authorization])

            val body = obj((httpRequest.body as TextContent).text)
            sentRequests += body

            val resp = if (calls == 1) toolResponse else finalResponse
            respond(resp, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        HttpClient(engine).use { client ->
            val executedCalls = mutableListOf<ResponseFunctionCall>()
            val intermediateTexts = mutableListOf<String>()

            val (status, resultJson) = runGoogleUpstream(
                client = client,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiToken = "test-token",
                model = "gemini-3.8-flash",
                request = sampleRequest(),
                maxToolRounds = 2,
                stream = false,
                executeTool = { call ->
                    executedCalls += call
                    """{"result":"found_fact"}"""
                },
                onIntermediateText = { text, _ -> intermediateTexts += text }
            )

            assertEquals(200, status)
            assertEquals(2, calls)
            assertEquals(1, executedCalls.size)
            assertEquals("lookup", executedCalls[0].name)

            // Verify round 2 request contents
            val round2Contents = sentRequests[1].getAsJsonArray("contents")
            assertEquals(3, round2Contents.size())
            val modelTurn = round2Contents[1].asJsonObject
            assertEquals("model", modelTurn.get("role").asString)
            assertEquals("sig_turn_1", modelTurn.getAsJsonArray("parts")[0].asJsonObject.get("thoughtSignature").asString)

            val userTurn = round2Contents[2].asJsonObject
            assertEquals("user", userTurn.get("role").asString)
            val funcResp = userTurn.getAsJsonArray("parts")[0].asJsonObject.getAsJsonObject("functionResponse")
            assertEquals("lookup", funcResp.get("name").asString)

            // Verify aggregated usage in final result
            val resultObj = obj(resultJson)
            val usage = resultObj.getAsJsonObject("usage")
            assertEquals(35, usage.get("prompt_tokens").asInt) // 10 + 25
            assertEquals(24, usage.get("completion_tokens").asInt) // (5+7) + (12+0)
            assertEquals(2, usage.get("qapi_api_calls").asInt)
            assertTrue(usage.get("qapi_usage_complete").asBoolean)
            assertEquals("Fact verified from tool output.", resultObj.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString)
        }
    }

    @Test
    fun `OAuth ya29 token sends Authorization Bearer header`() = runBlocking {
        val engine = MockEngine { httpRequest ->
            assertEquals("Bearer ya29.oauth-test-token", httpRequest.headers[HttpHeaders.Authorization])
            assertNull(httpRequest.headers["x-goog-api-key"])
            respond(finalResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        HttpClient(engine).use { client ->
            val (status, _) = runGoogleUpstream(
                client = client,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiToken = "ya29.oauth-test-token",
                model = "gemini-3.8-flash",
                request = sampleRequest(),
                maxToolRounds = 1,
                executeTool = { error("not called") }
            )
            assertEquals(200, status)
        }
    }

    @Test
    fun `stream chat emits SSE deltas and executes tool loop`() = runBlocking {
        var calls = 0
        val updates = mutableListOf<GoogleStreamUpdate>()
        val engine = MockEngine { httpRequest ->
            calls++
            val url = httpRequest.url.toString()
            assertTrue(url.contains(":streamGenerateContent?alt=sse"), "URL must contain :streamGenerateContent?alt=sse, got $url")

            val sseBody = if (calls == 1) {
                sseData(toolResponse)
            } else {
                sseData(finalResponse)
            }
            respond(sseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        HttpClient(engine).use { client ->
            val (status, resultJson) = runGoogleUpstream(
                client = client,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiToken = "test-token",
                model = "gemini-3.8-flash",
                request = sampleRequest(),
                maxToolRounds = 2,
                stream = true,
                executeTool = { _ -> """{"result":"ok"}""" },
                onUpdate = { updates += it }
            )

            assertEquals(200, status)
            assertEquals(2, calls)
            assertTrue(updates.any { it.phase == "generating" })
            val resultObj = obj(resultJson)
            assertEquals(2, resultObj.getAsJsonObject("usage").get("qapi_api_calls").asInt)
        }
    }

    @Test
    fun `failed later round preserves cost and usage of earlier successful rounds`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(toolResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("""{"error":{"message":"quota exceeded"}}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        HttpClient(engine).use { client ->
            val (status, resultJson) = runGoogleUpstream(
                client = client,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiToken = "test-token",
                model = "gemini-3.8-flash",
                request = sampleRequest(),
                maxToolRounds = 2,
                executeTool = { "result" }
            )

            assertEquals(503, status)
            val resultObj = obj(resultJson)
            val usage = resultObj.getAsJsonObject("usage")
            assertEquals(10, usage.get("prompt_tokens").asInt)
            assertEquals(12, usage.get("completion_tokens").asInt) // 5 + 7
            assertEquals(1, usage.get("qapi_api_calls").asInt)
        }
    }

    @Test
    fun `safety block in promptFeedback returns 502 with error details`() = runBlocking {
        val blockedResponse = """{
            "promptFeedback":{"blockReason":"SAFETY"}
        }"""
        val engine = MockEngine {
            respond(blockedResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        HttpClient(engine).use { client ->
            val (status, resultJson) = runGoogleUpstream(
                client = client,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiToken = "test-token",
                model = "gemini-3.8-flash",
                request = sampleRequest(),
                maxToolRounds = 1,
                executeTool = { "result" }
            )

            assertEquals(502, status)
            assertTrue(resultJson.contains("SAFETY"))
        }
    }

    @Test
    fun `stream tolerates non-streaming JSON response if upstream does not stream`() = runBlocking {
        val engine = MockEngine {
            respond(finalResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        HttpClient(engine).use { client ->
            val updates = mutableListOf<GoogleStreamUpdate>()
            val (status, resultJson) = runGoogleUpstream(
                client = client,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiToken = "test-token",
                model = "gemini-3.8-flash",
                request = sampleRequest(),
                maxToolRounds = 1,
                stream = true,
                executeTool = { "result" },
                onUpdate = { updates += it }
            )

            assertEquals(200, status)
            assertEquals(1, updates.size)
            assertEquals("Fact verified from tool output.", updates[0].text)
            assertNotNull(obj(resultJson).getAsJsonArray("choices"))
        }
    }

    @Test
    fun `stream error in SSE event throws exception and aborts without executing tool`() = runBlocking {
        val sseError = sseData("""{"error":{"message":"Gemini stream aborted by backend"}}""")
        val engine = MockEngine {
            respond(sseError, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        HttpClient(engine).use { client ->
            assertFailsWith<IllegalStateException> {
                runGoogleUpstream(
                    client = client,
                    endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                    apiToken = "test-token",
                    model = "gemini-3.8-flash",
                    request = sampleRequest(),
                    maxToolRounds = 1,
                    stream = true,
                    executeTool = { error("Must not execute tool on stream error") }
                )
            }
        }
    }
}
