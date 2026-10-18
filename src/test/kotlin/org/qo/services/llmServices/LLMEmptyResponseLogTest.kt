package org.qo.services.llmServices

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMEmptyResponseLogTest {
	@TempDir lateinit var tempDir: Path
	private val requester = LLMServices.LLMRequester(42, "Alice", "qq", groupId = 7, messageId = 123)

	@Test
	fun `record writes request body requester details and upstream response to log file`() {
		val path = tempDir.resolve("llm_empty_response.log")
		val request = LLMServices.NormalizedRequest(
			preset = "fast",
			model = "deepseek-chat",
			body = """{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}]}""",
			userContent = JsonObject().apply { addProperty("content", "你好") },
			currentUserText = "你好",
			enableMarkdown = false,
			reasoningEffort = LLMReasoningEffort.MEDIUM,
		)
		val upstreamResponse = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""

		LLMEmptyResponseLog.record(
			stage = "bot/complete",
			request = request,
			requester = requester,
			provider = "deepseek",
			model = "deepseek-chat",
			statusCode = 200,
			rawResponse = upstreamResponse,
			reason = "empty_assistant_content",
			accessRecordId = 99L,
			clientRequestId = "req-123",
			path = path,
		)

		val saved = Files.readString(path)
		assertTrue(saved.contains("===== LLM EMPTY RESPONSE ["))
		assertTrue(saved.contains("\"stage\": \"bot/complete\""))
		assertTrue(saved.contains("\"reason\": \"empty_assistant_content\""))
		assertTrue(saved.contains("\"provider\": \"deepseek\""))
		assertTrue(saved.contains("\"model\": \"deepseek-chat\""))
		assertTrue(saved.contains("\"preset\": \"fast\""))
		assertTrue(saved.contains("\"status_code\": 200"))
		assertTrue(saved.contains("\"access_record_id\": 99"))
		assertTrue(saved.contains("\"client_request_id\": \"req-123\""))
		assertTrue(saved.contains("\"source\": \"qq\""))
		assertTrue(saved.contains("\"uid\": 42"))
		assertTrue(saved.contains("\"name\": \"Alice\""))
		assertTrue(saved.contains("\"group_id\": 7"))
		assertTrue(saved.contains("\"message_id\": 123"))
		assertTrue(saved.contains("\"current_user_text\": \"你好\""))
		assertTrue(saved.contains("--- request_body ---"))
		assertTrue(saved.contains("\"messages\""))
		assertTrue(saved.contains("--- upstream_response ---"))
		assertTrue(saved.contains("\"content\": \"\""))
		assertTrue(saved.contains("===== END LLM EMPTY RESPONSE ====="))
	}

	@Test
	fun `record redacts sensitive credentials from request body and upstream response`() {
		val path = tempDir.resolve("llm_empty_response.log")
		val request = LLMServices.NormalizedRequest(
			preset = "fast",
			model = "deepseek-chat",
			body = """{"model":"deepseek-chat","token":"secret-token-123","password":"secret-password-456","messages":[{"role":"user","content":"Bearer secret-bearer-789"}]}""",
			userContent = JsonObject(),
			currentUserText = "hello",
			enableMarkdown = false,
			reasoningEffort = LLMReasoningEffort.MEDIUM,
		)
		val upstreamResponse = """{"error":"Bearer secret-upstream-token"}"""

		LLMEmptyResponseLog.record(
			stage = "chat/complete",
			request = request,
			requester = requester,
			provider = "deepseek",
			rawResponse = upstreamResponse,
			path = path,
		)

		val saved = Files.readString(path)
		assertTrue(saved.contains("<redacted>"))
		assertFalse(saved.contains("secret-token-123"))
		assertFalse(saved.contains("secret-password-456"))
		assertFalse(saved.contains("secret-bearer-789"))
		assertFalse(saved.contains("secret-upstream-token"))
	}

	@Test
	fun `concurrent empty response recordings are thread-safe and properly delimited`() {
		val path = tempDir.resolve("llm_empty_response.log")
		Files.writeString(path, "header log\n")
		val executor = Executors.newFixedThreadPool(4)
		try {
			val futures = (1..10).map { index ->
				executor.submit {
					LLMEmptyResponseLog.record(
						stage = "bot/complete",
						requester = requester,
						provider = "provider-$index",
						rawResponse = "empty-$index",
						path = path,
					)
				}
			}
			futures.forEach { it.get(10, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}

		val saved = Files.readString(path)
		assertTrue(saved.startsWith("header log\n"))
		val records = saved.split("===== END LLM EMPTY RESPONSE =====\n\n").dropLast(1)
		assertEquals(10, records.size)
		for (record in records) {
			assertEquals(1, Regex("===== LLM EMPTY RESPONSE ").findAll(record).count())
			assertEquals(1, Regex("provider-\\d+").findAll(record).count())
		}
	}
}
