package org.qo.services.llmServices

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
	fun `upserts memory by group category and subject`() = runBlocking {
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
	fun `keeps different keys for the same subject`() = runBlocking {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(100, "Alice", "favorite_drink", "喜欢红茶", "preference")
		service.upsertMemory(100, "Alice", "favorite_color", "喜欢蓝色", "preference")

		assertEquals(2, service.search(100, "Alice", 10).size)
	}

	@Test
	fun `scopes memories by group and ignores expired records`() = runBlocking {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(100, "活动", "event_time", "周六建筑比赛", "schedule")
		service.upsertMemory(200, "活动", "event_time", "周日钓鱼比赛", "schedule")
		service.upsertMemory(100, "旧活动", "event_status", "已经结束", "schedule", expiresAt = System.currentTimeMillis() - 1)

		val group100 = service.search(100, "活动")
		assertTrue(group100.any { it.fact.contains("周六") })
		assertFalse(group100.any { it.fact.contains("周日") || it.fact.contains("结束") })
	}

	@Test
	fun `forgets structured memory by id`() = runBlocking {
		val service = service(FakeMemoryRepository())
		val memory = service.upsertMemory(100, "Alice", "favorite_drink", "喜欢红茶", "preference")!!.record

		val removed = service.forget(100, memory.id, null)

		assertEquals(listOf(memory.id), removed.map { it.id })
		assertTrue(service.search(100, "Alice").isEmpty())
	}

	@Test
	fun `migrates legacy memory only once`() = runBlocking {
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

	@Test
	fun `infers tier from category and sets episodic ttl default`() = runBlocking {
		val service = service(FakeMemoryRepository())
		val core = service.upsertMemory(100, "Alice", "fav", "喜欢绿茶", category = "preference")!!
		assertEquals("core", core.record.tier)
		assertNull(core.record.expiresAt)

		val now = System.currentTimeMillis()
		val episodic = service.upsertMemory(100, "项目", "status", "第一阶段已完成", category = "status")!!
		assertEquals("episodic", episodic.record.tier)
		assertNotNull(episodic.record.expiresAt)
		assertTrue(episodic.record.expiresAt!! >= now + 13 * 86_400_000L)

		val semantic = service.upsertMemory(100, "文档", "link", "https://wiki.example.com", category = "general")!!
		assertEquals("semantic", semantic.record.tier)

		val explicit = service.upsertMemory(100, "Bob", "identity", "副服主", tier = "core", importance = 5)!!
		assertEquals("core", explicit.record.tier)
		assertEquals(5, explicit.record.importance)
	}

	@Test
	fun `merges user global memories when searching in group chat`() = runBlocking {
		val service = service(FakeMemoryRepository())
		// User 42's global preference
		service.upsertMemory(0L, "Alice", "global_drink", "无论在哪个群都只喝乌龙茶", tier = "core", sourceUid = "42")
		// Group 100's specific role
		service.upsertMemory(100L, "Alice", "group_role", "群100首席建筑师", tier = "semantic", sourceUid = "42")

		// Search in group 100 with includeUserGlobalUid = "42"
		val group100Matches = service.search(100L, "Alice", includeUserGlobalUid = "42")
		assertEquals(2, group100Matches.size)
		assertTrue(group100Matches.any { it.fact.contains("乌龙茶") })
		assertTrue(group100Matches.any { it.fact.contains("首席建筑师") })

		// Search in group 200 with includeUserGlobalUid = "42" gets global memory but not group 100 memory
		val group200Matches = service.search(200L, "Alice", includeUserGlobalUid = "42")
		assertEquals(1, group200Matches.size)
		assertEquals("无论在哪个群都只喝乌龙茶", group200Matches.single().fact)

		// Search in group 100 for other user 99 does not get user 42's global memory
		val group100OtherUser = service.search(100L, "Alice", includeUserGlobalUid = "99")
		assertEquals(1, group100OtherUser.size)
		assertEquals("群100首席建筑师", group100OtherUser.single().fact)

		// Tier filtering
		val onlyCore = service.search(100L, "Alice", includeUserGlobalUid = "42", tierFilter = "core")
		assertEquals(1, onlyCore.size)
		assertEquals("core", onlyCore.single().tier)
	}

	@Test
	fun `buildContext injects core memories unconditionally and partitions layers`() = runBlocking {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(100L, "规则", "core_rule", "群内严禁广告和刷屏", tier = "core", importance = 5)
		service.upsertMemory(100L, "活动", "event_progress", "主城地铁东线正在铺轨", tier = "episodic", importance = 3)
		service.upsertMemory(100L, "历史", "ancient_winner", "第一届建筑大赛冠军是 Charlie", tier = "semantic", importance = 2)

		// Unrelated query: "今天有什么好吃的"
		val context = service.buildContext(100L, "今天有什么好吃的", 42L)
		assertNotNull(context)
		// Core memory must be present even though "今天有什么好吃的" doesn't match "规则" or "广告"
		assertTrue(context.contains("【Core 核心常驻记忆】"))
		assertTrue(context.contains("群内严禁广告和刷屏"))
		// Episodic memory is present due to recent decay bonus
		assertTrue(context.contains("【Episodic 近期情景记忆】"))
		assertTrue(context.contains("主城地铁东线正在铺轨"))
		// Semantic memory about Charlie should NOT be included because question doesn't match
		assertFalse(context.contains("Charlie"))

		// Matching query: "建筑大赛"
		val matchedContext = service.buildContext(100L, "谁赢了建筑大赛", 42L)
		assertNotNull(matchedContext)
		assertTrue(matchedContext.contains("【Semantic 长期相关记忆】"))
		assertTrue(matchedContext.contains("Charlie"))
	}

	@Test
	fun `buildContext works for private chat with null groupId and valid uid`() = runBlocking {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(0L, "个人偏好", "code_style", "喜欢使用 Kotlin 和响应式编程", tier = "core", sourceUid = "42")

		val context = service.buildContext(null, "写一段代码", 42L)
		assertNotNull(context)
		assertTrue(context.contains("【Core 核心常驻记忆】"))
		assertTrue(context.contains("[core/global]"))
		assertTrue(context.contains("喜欢使用 Kotlin 和响应式编程"))

		// Missing both groupId and uid returns null
		assertNull(service.buildContext(null, "写一段代码", null))
	}

	@Test
	fun `forgets by subject and memoryKey in global and group scopes`() = runBlocking {
		val service = service(FakeMemoryRepository())
		service.upsertMemory(0L, "Alice", "temp_status", "正在参加会议", tier = "episodic", sourceUid = "42")
		assertEquals(1, service.search(0L, "Alice", includeUserGlobalUid = "42").size)

		val removed = service.forget(0L, null, null, sourceUid = "42", subject = "Alice", memoryKey = "temp_status")
		assertEquals(1, removed.size)
		assertEquals("正在参加会议", removed.single().fact)
		assertTrue(service.search(0L, "Alice", includeUserGlobalUid = "42").isEmpty())
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

		override suspend fun findByGroup(groupId: Long): List<LLMMemoryRecord> =
			records.values.filter { it.groupId == groupId }

		override suspend fun findByIdentity(groupId: Long, subject: String, memoryKey: String): LLMMemoryRecord? =
			records.values.firstOrNull { it.groupId == groupId && it.subject == subject && it.memoryKey == memoryKey }

		override suspend fun insert(record: LLMMemoryRecord): Boolean {
			if (findByIdentity(record.groupId, record.subject, record.memoryKey) != null) return false
			records[record.id] = record
			return true
		}

		override suspend fun update(record: LLMMemoryRecord) {
			records[record.id] = record
		}

		override suspend fun delete(groupId: Long, ids: List<String>) {
			ids.forEach { id -> records[id]?.takeIf { it.groupId == groupId }?.let { records.remove(id) } }
		}

		override suspend fun isMigrationComplete(key: String): Boolean = key in migrations

		override suspend fun markMigrationComplete(key: String) {
			migrations.add(key)
		}
	}
}
