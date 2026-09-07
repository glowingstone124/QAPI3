package org.qo.services.llmServices

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator

class LLMTokenStatsRepositoryTest {
	private lateinit var database: ReactiveDatabase
	private lateinit var repository: R2dbcLLMTokenStatsRepository
	private lateinit var service: LLMTokenStatisticsService

	@BeforeEach
	fun setUp() = runTest {
		val connectionFactory = ConnectionFactories.get(
			"r2dbc:h2:mem:///token_stats_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
		)
		database = ReactiveDatabase(
			client = DatabaseClient.create(connectionFactory),
			transactionalOperator = TransactionalOperator.create(R2dbcTransactionManager(connectionFactory)),
		)
		repository = R2dbcLLMTokenStatsRepository(database)
		repository.ensureSchemaInitialization()
		repository.awaitSchema()
		service = LLMTokenStatisticsService(repository)
	}

	@Test
	fun `recordGroupTokens inserts and accumulates cached and uncached tokens`() = runTest {
		val group = "test_group_alpha"
		assertTrue(repository.recordGroupTokens(group, cachedTokens = 100, uncachedTokens = 200, completionTokens = 50, totalTokens = 350))

		val initial = repository.getGroupStats(group)
		assertNotNull(initial)
		assertEquals(group, initial!!.groupName)
		assertEquals(100L, initial.cachedTokens)
		assertEquals(200L, initial.uncachedTokens)
		assertEquals(50L, initial.completionTokens)
		assertEquals(350L, initial.totalTokens)
		assertEquals(1L, initial.requestCount)

		// Second request in same group accumulates
		assertTrue(repository.recordGroupTokens(group, cachedTokens = 50, uncachedTokens = 100, completionTokens = 25, totalTokens = 175))

		val updated = repository.getGroupStats(group)
		assertNotNull(updated)
		assertEquals(150L, updated!!.cachedTokens)
		assertEquals(300L, updated.uncachedTokens)
		assertEquals(75L, updated.completionTokens)
		assertEquals(525L, updated.totalTokens)
		assertEquals(2L, updated.requestCount)
	}

	@Test
	fun `recordUserTokens inserts and accumulates cached and uncached tokens`() = runTest {
		val qqUid = 123456789L
		assertTrue(repository.recordUserTokens(qqUid, cachedTokens = 500, uncachedTokens = 150, completionTokens = 80, totalTokens = 730))

		val initial = repository.getUserStats(qqUid)
		assertNotNull(initial)
		assertEquals(qqUid, initial!!.qqUid)
		assertEquals(500L, initial.cachedTokens)
		assertEquals(150L, initial.uncachedTokens)
		assertEquals(80L, initial.completionTokens)
		assertEquals(730L, initial.totalTokens)
		assertEquals(1L, initial.requestCount)

		// Accumulate
		assertTrue(repository.recordUserTokens(qqUid, cachedTokens = 200, uncachedTokens = 50, completionTokens = 20, totalTokens = 270))

		val updated = repository.getUserStats(qqUid)
		assertNotNull(updated)
		assertEquals(700L, updated!!.cachedTokens)
		assertEquals(200L, updated.uncachedTokens)
		assertEquals(100L, updated.completionTokens)
		assertEquals(1000L, updated.totalTokens)
		assertEquals(2L, updated.requestCount)
	}

	@Test
	fun `listGroupStats and listUserStats order by total_tokens descending`() = runTest {
		repository.recordGroupTokens("group_low", cachedTokens = 10, uncachedTokens = 20, completionTokens = 10, totalTokens = 40)
		repository.recordGroupTokens("group_high", cachedTokens = 1000, uncachedTokens = 2000, completionTokens = 500, totalTokens = 3500)
		repository.recordGroupTokens("group_mid", cachedTokens = 100, uncachedTokens = 200, completionTokens = 50, totalTokens = 350)

		val groups = repository.listGroupStats()
		assertEquals(3, groups.size)
		assertEquals("group_high", groups[0].groupName)
		assertEquals("group_mid", groups[1].groupName)
		assertEquals("group_low", groups[2].groupName)

		repository.recordUserTokens(101L, cachedTokens = 10, uncachedTokens = 20, completionTokens = 10, totalTokens = 40)
		repository.recordUserTokens(102L, cachedTokens = 500, uncachedTokens = 500, completionTokens = 100, totalTokens = 1100)

		val users = repository.listUserStats()
		assertEquals(2, users.size)
		assertEquals(102L, users[0].qqUid)
		assertEquals(101L, users[1].qqUid)
	}

	@Test
	fun `service recordUsage computes uncached tokens when cacheHitTokens is provided`() = runTest {
		val usage = LLMServices.Usage(
			promptTokens = 1000,
			completionTokens = 150,
			totalTokens = 1150,
			cacheHitTokens = 750,
			cacheMissTokens = null,
		)

		service.recordUsage(groupName = "kotlin_study_group", qqUid = 998877L, usage = usage)

		val groupStats = service.getGroupStats("kotlin_study_group")
		assertNotNull(groupStats)
		assertEquals(750L, groupStats!!.cachedTokens)
		assertEquals(250L, groupStats.uncachedTokens) // 1000 - 750 = 250
		assertEquals(150L, groupStats.completionTokens)
		assertEquals(1150L, groupStats.totalTokens)
		assertEquals(1L, groupStats.requestCount)

		val userStats = service.getUserStats(998877L)
		assertNotNull(userStats)
		assertEquals(750L, userStats!!.cachedTokens)
		assertEquals(250L, userStats.uncachedTokens)
		assertEquals(150L, userStats.completionTokens)
		assertEquals(1150L, userStats.totalTokens)
	}

	@Test
	fun `service recordUsage respects explicit cacheMissTokens`() = runTest {
		val usage = LLMServices.Usage(
			promptTokens = 1000,
			completionTokens = 200,
			totalTokens = 1200,
			cacheHitTokens = 600,
			cacheMissTokens = 400,
		)

		service.recordUsage(groupName = "qapi_group", qqUid = 554433L, usage = usage)

		val groupStats = service.getGroupStats("qapi_group")
		assertNotNull(groupStats)
		assertEquals(600L, groupStats!!.cachedTokens)
		assertEquals(400L, groupStats.uncachedTokens)

		val userStats = service.getUserStats(554433L)
		assertNotNull(userStats)
		assertEquals(600L, userStats!!.cachedTokens)
		assertEquals(400L, userStats.uncachedTokens)
	}

	@Test
	fun `service recordUsage handles null groupName or zero qqUid`() = runTest {
		val usage = LLMServices.Usage(
			promptTokens = 500,
			completionTokens = 50,
			totalTokens = 550,
			cacheHitTokens = 100,
		)

		// When groupName is null, only user is recorded
		service.recordUsage(groupName = null, qqUid = 111L, usage = usage)
		assertNull(service.getGroupStats(""))
		assertNotNull(service.getUserStats(111L))

		// When qqUid is 0, only group is recorded
		service.recordUsage(groupName = "anonymous_group", qqUid = 0L, usage = usage)
		assertNotNull(service.getGroupStats("anonymous_group"))
		assertNull(service.getUserStats(0L))
	}
}
