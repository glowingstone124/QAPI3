package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.services.llmServices.tools.GenerateImageTool
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenerateImageToolTest {

	private lateinit var mockServer: HttpServer
	private var serverPort: Int = 0
	private val lastOpenAiRequestBody = AtomicReference<String>()
	private val lastBotRequestBody = AtomicReference<String>()
	private val lastBotAuthHeader = AtomicReference<String>()

	@BeforeEach
	fun setup() {
		mockServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		serverPort = mockServer.address.port

		mockServer.createContext("/v1/images/generations", HttpHandler { exchange: HttpExchange ->
			val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
			lastOpenAiRequestBody.set(body)

			if (body.contains("trigger_error")) {
				val errResponse = """{"error":{"message":"Safety check triggered","type":"invalid_request_error"}}"""
				val bytes = errResponse.toByteArray(StandardCharsets.UTF_8)
				exchange.sendResponseHeaders(400, bytes.size.toLong())
				exchange.responseBody.write(bytes)
				exchange.close()
				return@HttpHandler
			}

			val successResponse = """
				{
					"created": 1726000000,
					"data": [
						{
							"b64_json": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk",
							"revised_prompt": "A beautiful sunset over the mountains"
						}
					]
				}
			""".trimIndent()
			val bytes = successResponse.toByteArray(StandardCharsets.UTF_8)
			exchange.sendResponseHeaders(200, bytes.size.toLong())
			exchange.responseBody.write(bytes)
			exchange.close()
		})

		mockServer.createContext("/action/send_image", HttpHandler { exchange: HttpExchange ->
			lastBotAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
			val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
			lastBotRequestBody.set(body)

			if (body.contains("trigger_bot_error")) {
				val errResponse = """{"status":"failed","retcode":-1,"msg":"Network error to OneBot"}"""
				val bytes = errResponse.toByteArray(StandardCharsets.UTF_8)
				exchange.sendResponseHeaders(500, bytes.size.toLong())
				exchange.responseBody.write(bytes)
				exchange.close()
				return@HttpHandler
			}

			val okResponse = """{"status":"ok","group_id":946085440,"message_id":"123456"}"""
			val bytes = okResponse.toByteArray(StandardCharsets.UTF_8)
			exchange.sendResponseHeaders(200, bytes.size.toLong())
			exchange.responseBody.write(bytes)
			exchange.close()
		})

		mockServer.start()
	}

	@AfterEach
	fun teardown() {
		mockServer.stop(0)
	}

	@Test
	fun `tool definition specifies gpt-image-2-5 schema and 25 credits cost`() {
		val tool = GenerateImageTool()
		assertEquals("generate_image", tool.id)
		val definition = tool.definition
		assertEquals("function", definition.get("type").asString)
		val func = definition.getAsJsonObject("function")
		assertEquals("generate_image", func.get("name").asString)
		val desc = func.get("description").asString
		assertTrue(desc.contains("GPT-Image-2.5"))
		assertTrue(desc.contains("25"))
		val params = func.getAsJsonObject("parameters")
		val required = params.getAsJsonArray("required")
		assertEquals(1, required.size())
		assertEquals("prompt", required.get(0).asString)
		val props = params.getAsJsonObject("properties")
		assertTrue(props.has("prompt"))
		assertTrue(props.has("size"))
		assertTrue(props.has("quality"))
	}

	@Test
	fun `executing with non-qq source returns qq_only_tool`() = runBlocking {
		val tool = GenerateImageTool()
		val result = tool.execute(
			JsonObject().apply { addProperty("prompt", "cat") },
			LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "web")
		)
		val parsed = JsonParser.parseString(result).asJsonObject
		assertEquals("qq_only_tool", parsed.get("error").asString)
	}

	@Test
	fun `executing without valid user returns invalid_user`() = runBlocking {
		val tool = GenerateImageTool()
		val result = tool.execute(
			JsonObject().apply { addProperty("prompt", "cat") },
			LLMToolContext(groupId = 946085440L, uid = null, name = "tester", source = "qq")
		)
		val parsed = JsonParser.parseString(result).asJsonObject
		assertEquals("invalid_user", parsed.get("error").asString)
	}

	@Test
	fun `executing with blank prompt returns invalid_argument`() = runBlocking {
		val tool = GenerateImageTool()
		val result = tool.execute(
			JsonObject().apply { addProperty("prompt", "   ") },
			LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		)
		val parsed = JsonParser.parseString(result).asJsonObject
		assertEquals("invalid_argument", parsed.get("error").asString)
	}

	@Test
	fun `executing without providers configuration returns not_configured`() = runBlocking {
		val tool = GenerateImageTool()
		val result = tool.execute(
			JsonObject().apply { addProperty("prompt", "A cute cat") },
			LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		)
		val parsed = JsonParser.parseString(result).asJsonObject
		assertEquals("not_configured", parsed.get("error").asString)
	}

	@Test
	fun `insufficient credits returns insufficient_credits without calling OpenAI`() = runBlocking {
		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
		)
		val fakeQuota = FakeQuotaService(currentCredits = 10)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota)

		val result = tool.execute(
			JsonObject().apply { addProperty("prompt", "A cute cat") },
			LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		)
		val parsed = JsonParser.parseString(result).asJsonObject
		assertEquals("insufficient_credits", parsed.get("error").asString)
		assertEquals(0, fakeQuota.deductedAmount)
		assertEquals(null, lastOpenAiRequestBody.get())
	}

	@Test
	fun `successful image generation deducts 25 credits and sends to qbot`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
			qbotEndpoint = "http://127.0.0.1:$serverPort",
			qbotToken = "qbot-test-token",
		)
		val fakeQuota = FakeQuotaService(currentCredits = 50)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "画一只可爱的宇航员小猫，在月球表面看地球，唯美插画风格")
			addProperty("size", "1024x1024")
			addProperty("quality", "high")
		}

		val context = LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("generate_image", resultObj.get("tool").asString)
		assertEquals("success", resultObj.get("status").asString)
		assertEquals(25, resultObj.get("credits_consumed").asInt)
		assertTrue(resultObj.get("message").asString.contains("gpt-image-2.5"))

		// Verify 25 credits deducted
		assertEquals(25, fakeQuota.deductedAmount)
		assertEquals(0, fakeQuota.refundedAmount)
		assertEquals(25, fakeQuota.currentCredits)

		// Verify request sent to OpenAI mock
		assertNotNull(lastOpenAiRequestBody.get())
		val sentToOpenAi = JsonParser.parseString(lastOpenAiRequestBody.get()).asJsonObject
		assertEquals("gpt-image-2.5", sentToOpenAi.get("model").asString)
		assertEquals("画一只可爱的宇航员小猫，在月球表面看地球，唯美插画风格", sentToOpenAi.get("prompt").asString)
		assertEquals("1024x1024", sentToOpenAi.get("size").asString)
		assertEquals("high", sentToOpenAi.get("quality").asString)

		// Verify request forwarded to qbot /action/send_image
		assertEquals("qbot-test-token", lastBotAuthHeader.get())
		assertNotNull(lastBotRequestBody.get())
		val sentToBot = JsonParser.parseString(lastBotRequestBody.get()).asJsonObject
		assertEquals(946085440L, sentToBot.get("group_id").asLong)
		assertTrue(sentToBot.get("image").asString.startsWith("base64://iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"))
	}

	@Test
	fun `upstream OpenAI error refunds 25 credits`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
			qbotEndpoint = "http://127.0.0.1:$serverPort",
			qbotToken = "qbot-test-token",
		)
		val fakeQuota = FakeQuotaService(currentCredits = 50)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "trigger_error")
		}

		val context = LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("generation_failed", resultObj.get("error").asString)
		assertTrue(resultObj.get("message").asString.contains("Safety check triggered"))

		// Verify deduction then immediate refund
		assertEquals(25, fakeQuota.deductedAmount)
		assertEquals(25, fakeQuota.refundedAmount)
		assertEquals(50, fakeQuota.currentCredits)
	}

	@Test
	fun `qbot forward error refunds 25 credits`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
			qbotEndpoint = "http://127.0.0.1:$serverPort",
			qbotToken = "qbot-test-token",
		)
		val fakeQuota = FakeQuotaService(currentCredits = 50)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "trigger_bot_error")
		}

		val context = LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("bot_send_failed", resultObj.get("error").asString)

		// Verify deduction then immediate refund
		assertEquals(25, fakeQuota.deductedAmount)
		assertEquals(25, fakeQuota.refundedAmount)
		assertEquals(50, fakeQuota.currentCredits)
	}

	private class FakeQuotaService(
		var currentCredits: Int = 100,
	) : LLMDailyQuotaService(store = StubQuotaStore(), configuredDailyLimit = 120) {
		var deductedAmount: Int = 0
		var refundedAmount: Int = 0

		override suspend fun deductCredits(qqUid: Long, amount: Int, referenceId: String, kind: String): CreditDeductionResult {
			if (currentCredits < amount) {
				return CreditDeductionResult(
					success = false,
					remainingCredits = currentCredits,
					error = "账户剩余点数不足（当前点数：${currentCredits} 点，本次生成图片需要：${amount} 点 Credits）。请充值点数后再试。"
				)
			}
			currentCredits -= amount
			deductedAmount += amount
			return CreditDeductionResult(success = true, remainingCredits = currentCredits)
		}

		override suspend fun refundCredits(qqUid: Long, amount: Int, referenceId: String, kind: String): Boolean {
			currentCredits += amount
			refundedAmount += amount
			return true
		}
	}

	private class StubQuotaStore : LLMQuotaStore {
		override suspend fun reserve(quotaKey: String, requestKey: String, limit: Int, expiresAtEpochSeconds: Long): LLMQuotaStoreDecision? = null
		override suspend fun refund(reservation: LLMQuotaReservation): Int? = null
		override suspend fun used(quotaKey: String): Int? = 0
	}
}
