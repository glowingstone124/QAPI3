package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMChatHistoryServiceTest {
	private val repository = InMemoryChatHistoryRepository()
	private val service = LLMChatHistoryService(repository)

	@Test
	fun `archives idempotently and searches only the requested group`() = runBlocking {
		val groupOne = JsonArray().apply {
			add(message("m1", 1, "Alice", "讨论 Kotlin 协程", 1_700_000_000L))
		}
		service.archiveGroupContext(100, groupOne)
		service.archiveGroupContext(100, groupOne)
		service.archiveGroupContext(200, JsonArray().apply {
			add(message("m2", 2, "Bob", "讨论 Kotlin 服务端", 1_700_000_001L))
		})

		val results = service.search(100, "Kotlin")

		assertEquals(1, results.size)
		assertEquals("Alice", results.single().name)
		assertEquals(1_700_000_000_000L, results.single().time)
	}

	@Test
	fun `accepts live archive request format`() = runBlocking {
		val body = JsonObject().apply {
			add("messages", JsonArray().apply {
				add(message("onebot:9", 9, "Carol", "很久以前的决定", 123_456_789_000L))
			})
		}.toString()

		assertEquals(1, service.archiveRequest(300, body))
		assertTrue(service.search(300, "以前", uid = 9).single().content.contains("决定"))
	}

	private fun message(sourceId: String, uid: Long, name: String, content: String, time: Long): JsonObject = JsonObject().apply {
		addProperty("sourceId", sourceId)
		addProperty("uid", uid)
		addProperty("name", name)
		addProperty("content", content)
		addProperty("time", time)
	}

	private class InMemoryChatHistoryRepository : LLMChatHistoryRepository {
		private val records = linkedMapOf<Pair<Long, String>, LLMChatHistoryRecord>()

		override suspend fun insert(records: List<LLMChatHistoryRecord>): Int {
			var inserted = 0
			records.forEach { record ->
				if (this.records.putIfAbsent(record.groupId to record.sourceId, record) == null) inserted++
			}
			return inserted
		}

		override suspend fun search(
			groupId: Long,
			query: String,
			uid: Long?,
			fromTime: Long?,
			toTime: Long?,
			limit: Int,
		): List<LLMChatHistoryRecord> = records.values
			.filter { it.groupId == groupId }
			.filter { query.isBlank() || it.content.contains(query) || it.name.contains(query) }
			.filter { uid == null || it.uid == uid }
			.filter { fromTime == null || it.time >= fromTime }
			.filter { toTime == null || it.time <= toTime }
			.sortedByDescending { it.time }
			.take(limit)
	}
}
