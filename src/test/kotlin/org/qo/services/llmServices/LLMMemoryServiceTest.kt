package org.qo.services.llmServices

import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LLMMemoryServiceTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `spring selects the repository constructor`() {
		AnnotationConfigApplicationContext().use { context ->
			context.beanFactory.registerSingleton("memoryRepository", FakeMemoryRepository())
			context.register(LLMMemoryService::class.java)
			context.refresh()

			assertNotNull(context.getBean(LLMMemoryService::class.java))
		}
	}

	@Test
	fun `upserts memory by group category and subject`() {
		val service = service(FakeMemoryRepository())
		val created = service.upsertMemory(100, "Alice", "favorite_drink", "喜欢红茶", "preference", "42", "Alice")
		val updated = service.upsertMemory(100, "Alice", "favorite_drink", "现在喜欢咖啡", "preference", "42", "Alice")

		assertNotNull(created)
		assertNotNull(updated)
		assertTrue(created.created)
		assertFalse(updated.created)
		assertEquals(created.record.id, updated.record.id)
		assertEquals("现在喜欢咖啡", service.search(100, "Alice 咖啡").single().fact)
	}

	@Test
	fun `keeps different keys for the same subject`() {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(100, "Alice", "favorite_drink", "喜欢红茶", "preference")
		service.upsertMemory(100, "Alice", "favorite_color", "喜欢蓝色", "preference")

		assertEquals(2, service.search(100, "Alice", 10).size)
	}

	@Test
	fun `scopes memories by group and ignores expired records`() {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(100, "活动", "event_time", "周六建筑比赛", "schedule")
		service.upsertMemory(200, "活动", "event_time", "周日钓鱼比赛", "schedule")
		service.upsertMemory(100, "旧活动", "event_status", "已经结束", "schedule", expiresAt = System.currentTimeMillis() - 1)

		val group100 = service.search(100, "活动")
		assertTrue(group100.any { it.fact.contains("周六") })
		assertFalse(group100.any { it.fact.contains("周日") || it.fact.contains("结束") })
	}

	@Test
	fun `forgets structured memory by id`() {
		val service = service(FakeMemoryRepository())
		val memory = service.upsertMemory(100, "Alice", "favorite_drink", "喜欢红茶", "preference")!!.record

		val removed = service.forget(100, memory.id, null)

		assertEquals(listOf(memory.id), removed.map { it.id })
		assertTrue(service.search(100, "Alice").isEmpty())
	}

	@Test
	fun `migrates legacy memory only once`() {
		val repository = FakeMemoryRepository()
		val legacy = tempDir.resolve("rag/100/memory.txt")
		Files.createDirectories(legacy.parent)
		Files.writeString(legacy, "- Alice 喜欢红茶\n- 周六举行建筑比赛\n")
		val service = service(repository)

		service.migrateLegacyMemory()
		service.migrateLegacyMemory()

		assertEquals(2, repository.findByGroup(100).size)
		assertTrue(repository.isMigrationComplete("legacy-memory-txt-v1"))
		assertTrue(Files.exists(legacy))
	}

	private fun service(repository: LLMMemoryRepository): LLMMemoryService = LLMMemoryService(
		repository,
		LLMMemoryService.Config(
			legacyKnowledgeDir = tempDir.resolve("rag"),
			contextMaxItems = 5,
			contextMaxChars = 3000,
		)
	)

	private class FakeMemoryRepository : LLMMemoryRepository {
		private val records = linkedMapOf<String, LLMMemoryRecord>()
		private val migrations = mutableSetOf<String>()

		override fun findByGroup(groupId: Long): List<LLMMemoryRecord> =
			records.values.filter { it.groupId == groupId }

		override fun findByIdentity(groupId: Long, subject: String, memoryKey: String): LLMMemoryRecord? =
			records.values.firstOrNull { it.groupId == groupId && it.subject == subject && it.memoryKey == memoryKey }

		override fun insert(record: LLMMemoryRecord): Boolean {
			if (findByIdentity(record.groupId, record.subject, record.memoryKey) != null) return false
			records[record.id] = record
			return true
		}

		override fun update(record: LLMMemoryRecord) {
			records[record.id] = record
		}

		override fun delete(groupId: Long, ids: List<String>) {
			ids.forEach { id -> records[id]?.takeIf { it.groupId == groupId }?.let { records.remove(id) } }
		}

		override fun isMigrationComplete(key: String): Boolean = key in migrations

		override fun markMigrationComplete(key: String) {
			migrations.add(key)
		}
	}
}
