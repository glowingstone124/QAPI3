package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMResponsesUpstreamTest {
	private val requester = LLMServices.LLMRequester(42, "Alice", "qq", groupId = 7)
	private val request = LLMServices.NormalizedRequest(
		"fast", "test-model", """{"model":"test-model","messages":[{"role":"user","content":"question"}]}""",
		JsonPrimitive("question"), "question", false, LLMReasoningEffort.NONE,
		pricing = LLMModelPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
	)
	private val success = """{"id":"resp_success","model":"test-model","status":"completed","error":null,"incomplete_details":null,"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Valid answer"}]}],"usage":{"input_tokens":5,"output_tokens":7}}"""
	private val reservation = LLMQuotaReservation("quota", "request", 100, LLMQuotaView(100, 0, 100, 100))

	@Test
	fun `completed responses with null or omitted error return the actual assistant answer`() = runBlocking {
		val omitted = JsonParser.parseString(success).asJsonObject.apply { remove("error") }.toString()
		for (body in listOf(success, omitted)) {
			Fixture(listOf(body)).use { fixture ->
				val (status, result) = fixture.service.completeWithResponsesApi(request, requester, "bot", fixture.provider)
				assertEquals(200, status)
				val completion = JsonParser.parseString(result).asJsonObject
				assertEquals("Valid answer", completion.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString)
				assertEquals(7, completion.getAsJsonObject("usage").get("completion_tokens").asInt)
				assertEquals(1, fixture.sent.size)
			}
		}
	}

	@Test
	fun `failed incomplete and non null error responses remain errors and preserve usage`() = runBlocking {
		val failures = listOf(
			"""{"status":"failed","error":null,"output":[]}""",
			"""{"status":"incomplete","error":null,"incomplete_details":{"reason":"max_output_tokens"},"output":[]}""",
			"""{"status":"completed","error":{"code":"server_error","message":"failed"},"output":[]}""",
		)
		for (body in failures) {
			val response = JsonParser.parseString(body).asJsonObject.apply {
				add("usage", JsonParser.parseString("""{"input_tokens":5,"output_tokens":7}"""))
			}.toString()
			Fixture(listOf(response)).use { fixture ->
				val (status, result) = fixture.service.completeWithResponsesApi(request, requester, "bot", fixture.provider)
				assertEquals(502, status)
				val error = JsonParser.parseString(result).asJsonObject
				assertEquals("upstream_error", error.getAsJsonObject("error").get("code").asString)
				assertEquals(7, error.getAsJsonObject("usage").get("completion_tokens").asInt)
			}
		}
	}

	@Test
	fun `null error does not prevent executing tools and continuing the response`() = runBlocking {
		val tool = """{"id":"resp_tool","status":"completed","error":null,"output":[{"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}],"usage":{"input_tokens":2,"output_tokens":3}}"""
		Fixture(listOf(tool, success)).use { fixture ->
			Mockito.`when`(fixture.tools.execute("lookup", "{}", requester.toolContext("question"))).thenReturn("tool-result")
			val (status, result) = fixture.service.completeWithResponsesApi(request, requester, "bot", fixture.provider)
			assertEquals(200, status)
			assertEquals(2, fixture.sent.size)
			assertTrue(fixture.sent[1].contains("tool-result"))
			assertEquals(10, JsonParser.parseString(result).asJsonObject.getAsJsonObject("usage").get("completion_tokens").asInt)
		}
	}

	@Test
	fun `HTTP upstream failures retain status and response`() = runBlocking {
		val body = """{"error":{"message":"service unavailable"}}"""
		Fixture(listOf(body), HttpStatusCode.ServiceUnavailable).use { fixture ->
			val (status, result) = fixture.service.completeWithResponsesApi(request, requester, "bot", fixture.provider)
			assertEquals(503, status)
			assertEquals(body, result)
		}
	}

	@Test
	fun `invalid JSON still throws after archiving the upstream body`(): Unit = runBlocking {
		Fixture(listOf("<html>Bad gateway</html>")).use { fixture ->
			assertFailsWith<JsonSyntaxException> {
				fixture.service.completeWithResponsesApi(request, requester, "bot", fixture.provider)
			}
		}
	}

	@Test
	fun `completed streaming response with null error emits the actual assistant answer`() = runBlocking {
		val body = "data: {\"type\":\"response.completed\",\"response\":$success}\n\n"
		Fixture(listOf(body), contentType = "text/event-stream").use { fixture ->
			val quotaService = fixture.service.dailyQuotaService
			Mockito.doReturn(reservation.view).`when`(quotaService).settle(
				Mockito.eq(reservation) ?: reservation,
				Mockito.any(AiQuotaUsage::class.java) ?: AiQuotaUsage(0, 0, 0, null, BigDecimal.ZERO, 0),
			)
			val chunks = fixture.service.streamFromResponses(request, requester, -1, "web", fixture.provider, reservation).toList()
			assertTrue(chunks.any { it.contains("Valid answer") })
			assertFalse(chunks.any { it.contains("upstream_error") }, chunks.joinToString("\n"))
		}
	}

	@Test
	fun `stream incomplete response with null error reports failure without a cast exception`() = runBlocking {
		val body = """data: {"type":"response.incomplete","response":{"id":"resp_incomplete","status":"incomplete","error":null,"incomplete_details":{"reason":"max_output_tokens"},"output":[]}}

"""
		Fixture(listOf(body), contentType = "text/event-stream").use { fixture ->
			val chunks = fixture.service.streamFromResponses(request, requester, -1, "web", fixture.provider, reservation).toList()
			assertTrue(chunks.any { it.contains("Responses API 请求失败") })
			assertFalse(chunks.any { it.contains("ClassCastException") })
		}
	}

	@Test
	fun `stream error event retains the user facing upstream message`() = runBlocking {
		val body = """data: {"type":"error","code":"server_error","message":"upstream failure"}

"""
		Fixture(listOf(body), contentType = "text/event-stream").use { fixture ->
			val chunks = fixture.service.streamFromResponses(request, requester, -1, "web", fixture.provider, reservation).toList()
			assertTrue(chunks.any { it.contains("upstream failure") })
		}
	}

	private class Fixture(
		responses: List<String>, status: HttpStatusCode = HttpStatusCode.OK, contentType: String = "application/json",
	) : AutoCloseable {
		val sent = mutableListOf<String>()
		val service = Mockito.mock(LLMServices::class.java, Mockito.RETURNS_DEEP_STUBS)
		val provider = Mockito.mock(LLMProvider::class.java)
		val tools = Mockito.mock(LLMToolService::class.java)
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		private val client = HttpClient(MockEngine { outgoing ->
			sent.add((outgoing.body as TextContent).text)
			respond(responses[sent.lastIndex], status, headersOf(HttpHeaders.ContentType, contentType))
		})

		init {
			Mockito.`when`(service.client).thenReturn(client)
			Mockito.`when`(service.maxToolRounds).thenReturn(3)
			Mockito.`when`(service.toolService).thenReturn(tools)
			Mockito.`when`(service.initializationScope).thenReturn(scope)
			Mockito.`when`(service.tokenStatisticsService).thenReturn(null)
			Mockito.`when`(tools.definitions(emptySet(), "qq")).thenReturn(JsonArray())
			Mockito.`when`(tools.usesRemoteSearch()).thenReturn(true)
			Mockito.`when`(provider.name).thenReturn("test-provider")
			Mockito.`when`(provider.responsesUrl).thenReturn("https://gateway.example/responses")
			Mockito.`when`(provider.apiToken).thenReturn("test-token")
		}

		override fun close() {
			scope.cancel()
			client.close()
		}
	}
}
