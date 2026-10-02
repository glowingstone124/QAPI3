package org.qo.services.llmServices

import com.google.gson.JsonArray
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

class LLMToolFailureLogTest {
	@TempDir lateinit var tempDir: Path
	private val context = LLMToolContext(7, "42", "Alice", currentMessageId = 123, source = "qq", conversationKey = "qq:7:42")

	@Test
	fun `parse failure archive includes readable context and full multiline model output`() {
		val path = tempDir.resolve("llm/toolcall-failure.log")
		val content = "<tool_call>无法解析\n${"x".repeat(1500)}\n末尾诊断</tool_call>"
		val response = JsonObject().apply {
			addProperty("id", "upstream-123")
			add("choices", JsonArray().apply {
				add(JsonObject().apply { add("message", JsonObject().apply { addProperty("content", content) }) })
			})
		}.toString()

		LLMToolFailureLog(path).recordInvalidToolCall(response, context, "bot", "provider-a", "model-a", 2)

		val saved = Files.readString(path)
		for (field in listOf(
			"\"stage\": \"parse\"", "\"error\": \"invalid_tool_call\"", "\"request_source\": \"bot\"",
			"\"provider\": \"provider-a\"", "\"model\": \"model-a\"", "\"round\": 2",
			"\"message_id\": 123", "\"conversation_key\": \"qq:7:42\"", "\"id\": \"upstream-123\"",
		)) {
			assertTrue(saved.contains(field), field)
		}
		assertTrue(saved.contains("--- upstream_response ---"))
		assertTrue(saved.contains("--- assistant_content ---\n  <tool_call>无法解析\n  ${"x".repeat(1500)}\n  末尾诊断</tool_call>"))
		assertTrue(Regex("\\\"time\\\": \\\"\\d{4}-\\d{2}-\\d{2}T[^\\\"]+Z\\\"").containsMatchIn(saved))
	}

	@Test
	fun `execution details are expanded and credentials and binary payloads are redacted`() {
		val path = tempDir.resolve("toolcall-failure.log")
		val args = """{"query":"上海","nested":{"api_key":"secret-key"},"header":"Bearer secret-bearer","image":"data:image/png;base64,c2VjcmV0","arguments":"{\"password\":\"secret-password\"}"}"""
		val result = """{"error":"tool_error","message":"失败","token":"secret-token"}"""

		LLMToolFailureLog(path).record(context, "execute", "tool_error", "失败", linkedMapOf("arguments" to args, "result" to result), tool = "web_search")

		val saved = Files.readString(path)
		assertTrue(saved.contains("\"tool\": \"web_search\""))
		assertTrue(saved.contains("--- arguments ---\n  {\n    \"query\": \"上海\""))
		assertTrue(saved.contains("--- result ---"))
		assertTrue(saved.contains("<redacted>"))
		for (secret in listOf("secret-key", "secret-bearer", "c2VjcmV0", "secret-password", "secret-token")) {
			assertFalse(saved.contains(secret), secret)
		}
	}

	@Test
	fun `concurrent failures append complete records without replacing previous logs`() {
		val path = tempDir.resolve("toolcall-failure.log")
		Files.writeString(path, "previous log\n")
		val executor = Executors.newFixedThreadPool(4)
		try {
			val futures = (1..12).map { index -> executor.submit {
				LLMToolFailureLog(path).recordInvalidToolCall("<tool_call>failure-$index</tool_call>", context, "bot", "provider", "model", index)
			} }
			futures.forEach { it.get(10, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}

		val saved = Files.readString(path)
		assertTrue(saved.startsWith("previous log\n"))
		val records = saved.split("===== END LLM TOOL FAILURE =====\n\n").dropLast(1)
		assertEquals(12, records.size)
		for (record in records) {
			assertEquals(1, Regex("===== LLM TOOL FAILURE ").findAll(record).count())
			assertEquals(1, Regex("<tool_call>failure-\\d+</tool_call>").findAll(record).count())
		}
	}

	@Test
	fun `failure to write the archive does not throw to the request handler`() {
		val parentFile = tempDir.resolve("not-a-directory")
		Files.writeString(parentFile, "keep")
		LLMToolFailureLog(parentFile.resolve("failure.log")).recordInvalidToolCall("<tool_call>broken", context, "bot", "provider", "model", 1)
		assertEquals("keep", Files.readString(parentFile))
	}
}
