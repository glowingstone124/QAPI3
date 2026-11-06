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
import org.junit.jupiter.api.io.TempDir
import org.qo.services.llmServices.tools.GenerateImageTool
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenerateImageToolTest {
	@TempDir lateinit var tempDir: Path

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
				exchange.responseHeaders.set("x-request-id", "mock-image-400")
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
	fun `tool definition specifies image schema and 25 credits cost`() {
		val tool = GenerateImageTool()
		assertEquals("generate_image", tool.id)
		val definition = tool.definition
		assertEquals("function", definition.get("type").asString)
		val func = definition.getAsJsonObject("function")
		assertEquals("generate_image", func.get("name").asString)
		val desc = func.get("description").asString
		assertTrue(desc.contains("GPT Image 2.6"))
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
	fun `flare does not let generated tool arguments opt into complexity`() = runBlocking {
		val tool = GenerateImageTool(LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-key",
			model = "gpt-image-2.6-flare",
			creditsCost = 0,
		))
		val properties = tool.definition.getAsJsonObject("function")
			.getAsJsonObject("parameters").getAsJsonObject("properties")
		assertFalse(properties.has("complexity"))
		val result = tool.execute(JsonObject().apply {
			addProperty("prompt", "cat")
			addProperty("complexity", "medium")
		}, LLMToolContext(groupId = null, uid = "10001", name = "tester", source = "qq"))
		assertEquals("success", JsonParser.parseString(result).asJsonObject.get("status").asString)
		val sent = JsonParser.parseString(lastOpenAiRequestBody.get()).asJsonObject
		assertEquals("gpt-image-2.6-flare", sent.get("model").asString)
		assertEquals("auto", sent.get("quality").asString)
		assertFalse(sent.has("complexity"))
	}

	@Test
	fun `complexity is controlled by provider configuration`() = runBlocking {
		val tool = GenerateImageTool(LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-key",
			model = "gpt-image-2.6-flare",
			complexity = "high",
			creditsCost = 0,
		))
		tool.execute(JsonObject().apply {
			addProperty("prompt", "cat")
			addProperty("complexity", "medium")
		}, LLMToolContext(groupId = null, uid = "10001", name = "tester", source = "qq"))
		val sent = JsonParser.parseString(lastOpenAiRequestBody.get()).asJsonObject
		assertEquals("high", sent.get("complexity").asString)
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
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations?token=private-query-token",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
			qbotEndpoint = "http://127.0.0.1:$serverPort",
			qbotToken = "qbot-test-token",
		)
		val fakeQuota = FakeQuotaService(currentCredits = 50)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)
		val logPath = tempDir.resolve("image-failure.log")
		tool.failureLog = LLMToolFailureLog(logPath)

		val args = JsonObject().apply {
			addProperty("prompt", "trigger_error private image prompt")
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
		val saved = Files.readString(logPath)
		assertTrue(saved.contains("\"stage\": \"image_generation\""))
		assertTrue(saved.contains("\"http_status\": 400"))
		assertTrue(saved.contains("mock-image-400"))
		assertTrue(saved.contains("\"quality\": \"auto\""))
		assertTrue(saved.contains("Safety check triggered"))
		assertFalse(saved.contains("test-sk-key-12345"))
		assertFalse(saved.contains("private-query-token"))
		assertFalse(saved.contains("private image prompt"))
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

	@Test
	fun `successful image generation prioritizes free weekly quota over paid credits`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
		)
		// 50 weekly units available, 0 paid credits
		val fakeQuota = FakeQuotaService(availableWeeklyUnits = 50, currentCredits = 0)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "画一只可爱的宇航员小猫，唯美插画风格")
		}
		val context = LLMToolContext(groupId = null, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("success", resultObj.get("status").asString)
		assertEquals(25, resultObj.get("credits_consumed").asInt)
		assertEquals(25, resultObj.get("weekly_units_deducted").asInt)
		assertEquals(0, resultObj.get("paid_credits_deducted").asInt)
		assertEquals(25, fakeQuota.availableWeeklyUnits)
		assertEquals(0, fakeQuota.currentCredits)
	}

	@Test
	fun `split deduction consumes remaining free quota first and paid credits second`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
		)
		// 10 weekly units available, 30 paid credits
		val fakeQuota = FakeQuotaService(availableWeeklyUnits = 10, currentCredits = 30)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "画一只可爱的宇航员小猫，唯美插画风格")
		}
		val context = LLMToolContext(groupId = null, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("success", resultObj.get("status").asString)
		assertEquals(25, resultObj.get("credits_consumed").asInt)
		assertEquals(10, resultObj.get("weekly_units_deducted").asInt)
		assertEquals(15, resultObj.get("paid_credits_deducted").asInt)
		assertEquals(0, fakeQuota.availableWeeklyUnits)
		assertEquals(15, fakeQuota.currentCredits)
	}

	@Test
	fun `split deduction refunds both weekly units and paid credits on error`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
		)
		// 10 weekly units available, 30 paid credits
		val fakeQuota = FakeQuotaService(availableWeeklyUnits = 10, currentCredits = 30)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)
		tool.failureLog = LLMToolFailureLog(tempDir.resolve("split-image-failure.log"))

		val args = JsonObject().apply {
			addProperty("prompt", "trigger_error")
		}
		val context = LLMToolContext(groupId = null, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("generation_failed", resultObj.get("error").asString)
		assertEquals(10, fakeQuota.availableWeeklyUnits)
		assertEquals(30, fakeQuota.currentCredits)
		assertEquals(10, fakeQuota.refundedWeeklyAmount)
		assertEquals(15, fakeQuota.refundedPaidAmount)
	}

	@Test
	fun `successful image generation in private chat pushes user_id to bot`() = runBlocking {
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
		val fakeQuota = FakeQuotaService(availableWeeklyUnits = 50, currentCredits = 50)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "画一只在雨中漫步的小猫，水彩风格")
		}

		val context = LLMToolContext(groupId = null, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("generate_image", resultObj.get("tool").asString)
		assertEquals("success", resultObj.get("status").asString)
		assertTrue(resultObj.get("message").asString.contains("当前 QQ 私聊"))

		// Verify request forwarded to qbot /action/send_image with user_id instead of group_id
		assertEquals("qbot-test-token", lastBotAuthHeader.get())
		assertNotNull(lastBotRequestBody.get())
		val sentToBot = JsonParser.parseString(lastBotRequestBody.get()).asJsonObject
		assertEquals(10001L, sentToBot.get("user_id").asLong)
		assertEquals(null, sentToBot.get("group_id"))
		assertTrue(sentToBot.get("image").asString.startsWith("base64://"))
	}

	@Test
	fun `image generation in group deducts from group units first`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
		)
		val fakeQuota = FakeQuotaService(availableGroupUnits = 25, availableWeeklyUnits = 50, currentCredits = 100)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "画一只群吉祥物，Q版风格")
		}
		val context = LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("success", resultObj.get("status").asString)
		assertEquals(25, resultObj.get("credits_consumed").asInt)
		assertEquals(25, resultObj.get("group_units_deducted").asInt)
		assertEquals(0, resultObj.get("weekly_units_deducted").asInt)
		assertEquals(0, resultObj.get("paid_credits_deducted").asInt)
		assertEquals(0, fakeQuota.availableGroupUnits)
		assertEquals(50, fakeQuota.availableWeeklyUnits)
		assertEquals(100, fakeQuota.currentCredits)
	}

	@Test
	fun `split deduction consumes group first then weekly then paid credits`() = runBlocking {
		val httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()

		val config = LLMImageGenerationConfig(
			endpointUrl = "http://127.0.0.1:$serverPort/v1/images/generations",
			apiToken = "test-sk-key-12345",
			model = "gpt-image-2.5",
			creditsCost = 25,
			enabled = true,
		)
		val fakeQuota = FakeQuotaService(availableGroupUnits = 10, availableWeeklyUnits = 10, currentCredits = 50)
		val tool = GenerateImageTool(config = config, quotaService = fakeQuota, httpClient = httpClient)

		val args = JsonObject().apply {
			addProperty("prompt", "画一只猫")
		}
		val context = LLMToolContext(groupId = 946085440L, uid = "10001", name = "tester", source = "qq")
		val result = tool.execute(args, context)
		val resultObj = JsonParser.parseString(result).asJsonObject

		assertEquals("success", resultObj.get("status").asString)
		assertEquals(25, resultObj.get("credits_consumed").asInt)
		assertEquals(10, resultObj.get("group_units_deducted").asInt)
		assertEquals(10, resultObj.get("weekly_units_deducted").asInt)
		assertEquals(5, resultObj.get("paid_credits_deducted").asInt)
		assertEquals(0, fakeQuota.availableGroupUnits)
		assertEquals(0, fakeQuota.availableWeeklyUnits)
		assertEquals(45, fakeQuota.currentCredits)
	}

	@Test
	fun `spring can instantiate GenerateImageTool via autowiring without conflict`() {
		val context = org.springframework.context.annotation.AnnotationConfigApplicationContext()
		context.beanFactory.registerSingleton("reloadableLLMProvider", org.mockito.Mockito.mock(ReloadableLLMProvider::class.java))
		context.beanFactory.registerSingleton("dailyQuotaService", org.mockito.Mockito.mock(LLMDailyQuotaService::class.java))
		context.register(GenerateImageTool::class.java)
		context.refresh()
		val tool = context.getBean(GenerateImageTool::class.java)
		assertNotNull(tool)
		assertEquals("generate_image", tool.id)
		context.close()
	}

	private class FakeQuotaService(
		var availableGroupUnits: Int = 0,
		var availableWeeklyUnits: Int = 0,
		var currentCredits: Int = 100,
	) : LLMDailyQuotaService(store = StubQuotaStore(), configuredDailyLimit = 120) {
		var deductedGroupAmount: Int = 0
		var deductedWeeklyAmount: Int = 0
		var deductedPaidAmount: Int = 0
		var refundedGroupAmount: Int = 0
		var refundedWeeklyAmount: Int = 0
		var refundedPaidAmount: Int = 0

		val deductedAmount: Int get() = deductedGroupAmount + deductedWeeklyAmount + deductedPaidAmount
		val refundedAmount: Int get() = refundedGroupAmount + refundedWeeklyAmount + refundedPaidAmount

		override suspend fun deductQuota(
			qqUid: Long,
			amount: Int,
			referenceId: String,
			hasAccount: Boolean,
			now: java.time.Instant,
			groupId: Long?,
		): CreditDeductionResult {
			val groupDeduct = if (groupId != null) minOf(amount, availableGroupUnits) else 0
			val remAfterGroup = amount - groupDeduct
			val weeklyDeduct = minOf(remAfterGroup, availableWeeklyUnits)
			val paidDeduct = remAfterGroup - weeklyDeduct
			if (currentCredits < paidDeduct) {
				return CreditDeductionResult(
					success = false,
					remainingCredits = currentCredits,
					weeklyDeducted = 0,
					paidDeducted = 0,
					groupDeducted = 0,
					error = "账户剩余额度不足（剩余免费额度：${availableWeeklyUnits} 点，Paid Credits：${currentCredits} 点，本次生成图片需要：${amount} 点）。请充值点数或等待下周免费额度重置。"
				)
			}
			availableGroupUnits -= groupDeduct
			deductedGroupAmount += groupDeduct
			availableWeeklyUnits -= weeklyDeduct
			deductedWeeklyAmount += weeklyDeduct
			currentCredits -= paidDeduct
			deductedPaidAmount += paidDeduct
			return CreditDeductionResult(
				success = true,
				remainingCredits = currentCredits,
				weeklyDeducted = weeklyDeduct,
				paidDeducted = paidDeduct,
				groupDeducted = groupDeduct,
			)
		}

		override suspend fun refundQuota(
			qqUid: Long,
			weeklyAmount: Int,
			paidAmount: Int,
			referenceId: String,
			now: java.time.Instant,
			groupId: Long?,
			groupAmount: Int,
		): Boolean {
			availableGroupUnits += groupAmount
			refundedGroupAmount += groupAmount
			availableWeeklyUnits += weeklyAmount
			refundedWeeklyAmount += weeklyAmount
			currentCredits += paidAmount
			refundedPaidAmount += paidAmount
			return true
		}
	}

	private class StubQuotaStore : LLMQuotaStore {
		override suspend fun reserve(quotaKey: String, requestKey: String, limit: Int, expiresAtEpochSeconds: Long): LLMQuotaStoreDecision? = null
		override suspend fun refund(reservation: LLMQuotaReservation): Int? = null
		override suspend fun used(quotaKey: String): Int? = 0
	}
}
