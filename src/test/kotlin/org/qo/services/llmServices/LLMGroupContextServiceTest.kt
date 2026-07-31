package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMGroupContextServiceTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `keeps newest messages and removes duplicated current question`() = runBlocking {
		val service = service(recentMessages = 2, summaryMinMessages = 1)
		val context = service.buildContext(
			groupId = 100,
			groupContext = messages(1..5).apply { add(message(9, 42, "Alice", "@恋恋 current question")) },
			currentQuestion = "current question",
			currentUid = 42,
		) { _, old -> "摘要包含 ${old.joinToString { it.content }}" }

		assertTrue(context!!.contains("摘要包含 message-1, message-2, message-3"))
		assertTrue(context.contains("message-4"))
		assertTrue(context.contains("message-5"))
		assertFalse(context.contains("@恋恋 current question"))
	}

	@Test
	fun `incrementally summarizes only newly aged messages`() = runBlocking {
		val service = service(recentMessages = 2, summaryMinMessages = 1)
		val summarizedBatches = mutableListOf<List<String>>()
		val summarizer: suspend (String?, List<GroupChatEntry>) -> String? = { existing, entries ->
			summarizedBatches.add(entries.map { it.content })
			listOfNotNull(existing, entries.joinToString { it.content }).joinToString(" | ")
		}

		service.buildContext(100, messages(1..5), "question", 99, summarizer)
		val context = service.buildContext(100, messages(1..7), "question", 99, summarizer)

		assertEquals(listOf("message-1", "message-2", "message-3"), summarizedBatches[0])
		assertEquals(listOf("message-4", "message-5"), summarizedBatches[1])
		assertTrue(context!!.contains("message-6"))
		assertTrue(context.contains("message-7"))
	}

	@Test
	fun `summary failure still keeps newest raw history`() = runBlocking {
		val service = service(recentMessages = 2, summaryMinMessages = 1)
		val context = service.buildContext(100, messages(1..5), "question", 99) { _, _ -> null }

		assertTrue(context!!.contains("message-4"))
		assertTrue(context.contains("message-5"))
	}

	private fun service(recentMessages: Int, summaryMinMessages: Int): LLMGroupContextService =
		LLMGroupContextService(LLMGroupContextService.Config(
			summaryDir = tempDir.resolve("summaries"),
			recentMaxMessages = recentMessages,
			recentMaxChars = 10_000,
			pendingMaxChars = 10_000,
			summaryMinNewMessages = summaryMinMessages,
			summaryMinNewChars = 100_000,
			summaryMaxChars = 3000,
			summaryEnabled = true,
		))

	private fun messages(range: IntRange): JsonArray = JsonArray().apply {
		range.forEach { add(message(it.toLong(), it.toLong(), "user-$it", "message-$it")) }
	}

	private fun message(time: Long, uid: Long, name: String, content: String): JsonObject = JsonObject().apply {
		addProperty("time", time)
		addProperty("uid", uid)
		addProperty("name", name)
		addProperty("content", content)
	}
}
