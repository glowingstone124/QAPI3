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

class LLMErrorLogTest {
	@TempDir lateinit var tempDir: Path
	private val requester = LLMServices.LLMRequester(42, "Alice", "qq", groupId = 7, messageId = 123)

	@Test
	fun `response archive retains complete output null fields usage and failure context`() {
		val path = tempDir.resolve("llm/llm-error.log")
		val output = "开始\n${"x".repeat(16000)}\n末尾诊断"
		val body = JsonObject().apply {
			addProperty("id", "resp_failure")
			addProperty("status", "incomplete")
			add("error", null)
			add("incomplete_details", JsonObject().apply { addProperty("reason", "max_output_tokens") })
			add("usage", JsonObject().apply { addProperty("input_tokens", 5); addProperty("output_tokens", 17) })
			addProperty("output_text", output)
		}.toString()

		LLMErrorLog.recordResponse("bot/responses-round-2", body, "provider-a", requester, "model-a", 2, 200, 99, path)

		val saved = Files.readString(path)
		for (field in listOf(
			"stage=bot/responses-round-2", "provider=provider-a", "model=model-a", "round=2", "http_status=200",
			"source=qq", "uid=42", "group_id=7", "message_id=123", "access_record_id=99",
			"\"id\": \"resp_failure\"", "\"error\": null", "\"status\": \"incomplete\"",
			"\"reason\": \"max_output_tokens\"", "\"output_tokens\": 17", "末尾诊断",
		)) assertTrue(saved.contains(field), field)
		assertTrue(saved.contains("x".repeat(16000)))
		assertTrue(saved.contains("--- upstream_response ---\n  {\n"))
		assertFalse(saved.contains("clipped"))
	}

	@Test
	fun `response credentials and embedded payloads are redacted without losing diagnostic nulls`() {
		val path = tempDir.resolve("llm-error.log")
		val body = """{"status":"failed","error":{"message":"failure"},"nested":{"API_KEY":"secret-key","refresh_token":"secret-refresh","Cookie":"secret-cookie"},"header":"Bearer secret-bearer","image":"data:image/png;base64,c2VjcmV0","arguments":"{\"password\":\"secret-password\"}","incomplete_details":null}"""

		LLMErrorLog.recordResponse("bot/responses-round-1", body, path = path)

		val saved = Files.readString(path)
		assertTrue(saved.contains("\"incomplete_details\": null"))
		assertTrue(saved.contains("\"message\": \"failure\""))
		assertTrue(saved.contains("<redacted>"))
		for (secret in listOf("secret-key", "secret-refresh", "secret-cookie", "secret-bearer", "c2VjcmV0", "secret-password")) {
			assertFalse(saved.contains(secret), secret)
		}
	}

	@Test
	fun `non JSON upstream body is retained and redacted`() {
		val path = tempDir.resolve("llm-error.log")
		LLMErrorLog.recordResponse("bot/responses-round-1", "Bad gateway\nBearer fake-secret\nupstream detail", httpStatus = 502, path = path)
		val saved = Files.readString(path)
		assertTrue(saved.contains("http_status=502"))
		assertTrue(saved.contains("  Bad gateway\n  Bearer <redacted>\n  upstream detail"))
		assertFalse(saved.contains("fake-secret"))
	}

	@Test
	fun `concurrent response failures append complete entries and preserve old exception logs`() {
		val path = tempDir.resolve("llm-error.log")
		Files.writeString(path, "previous exception log\n")
		val executor = Executors.newFixedThreadPool(4)
		try {
			val futures = (1..12).map { index -> executor.submit {
				LLMErrorLog.recordResponse("bot/responses-round-1", "failure-$index", path = path)
			} }
			futures.forEach { it.get(10, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}
		val saved = Files.readString(path)
		assertTrue(saved.startsWith("previous exception log\n"))
		val records = saved.split("===== END LLM UPSTREAM FAILURE =====\n\n").dropLast(1)
		assertEquals(12, records.size)
		for (record in records) {
			assertEquals(1, Regex("===== LLM UPSTREAM FAILURE ").findAll(record).count())
			assertEquals(1, Regex("failure-\\d+").findAll(record).count())
		}
	}

	@Test
	fun `archive write failure does not replace the original request failure`() {
		val parent = tempDir.resolve("not-a-directory")
		Files.writeString(parent, "keep")
		LLMErrorLog.recordResponse("bot/responses-round-1", "failure", path = parent.resolve("llm-error.log"))
		assertEquals("keep", Files.readString(parent))
	}
}
