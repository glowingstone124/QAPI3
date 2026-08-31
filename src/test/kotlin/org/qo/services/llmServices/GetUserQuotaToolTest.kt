package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.qo.services.llmServices.tools.GetUserQuotaTool
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GetUserQuotaToolTest {
	@Test
	fun `tool returns remaining quota and guest details when user is not registered`() = runBlocking {
		val store = InMemoryQuotaStore()
		val quotaService = LLMDailyQuotaService(store, 50, 20, "Asia/Shanghai")
		val now = Instant.now()
		val tool = GetUserQuotaTool(quotaService)

		// Reserve 5 turns as guest
		val principal = LLMPrincipal(123456L, "tester", LLMSource.QQ, "123456", hasAccount = false)
		for (i in 1..5) {
			quotaService.reserve(principal, "req-$i", now)
		}

		val context = LLMToolContext(groupId = 100L, uid = "123456", name = "tester")
		val resultJson = tool.execute(JsonObject(), context)
		val result = JsonParser.parseString(resultJson).asJsonObject

		assertEquals("get_user_quota", result.get("tool").asString)
		assertEquals(123456L, result.get("uid").asLong)
		assertEquals(20, result.get("limit").asInt)
		assertEquals(5, result.get("used").asInt)
		assertEquals(15, result.get("remaining").asInt)
		assertFalse(result.get("has_qo_account").asBoolean)
		assertEquals("游客", result.get("account_type").asString)
		assertTrue(result.has("tip"))
	}

	@Test
	fun `tool supports querying target_uid from args`() = runBlocking {
		val store = InMemoryQuotaStore()
		val quotaService = LLMDailyQuotaService(store, 50, 20, "Asia/Shanghai")
		val now = Instant.now()
		val tool = GetUserQuotaTool(quotaService)

		val targetPrincipal = LLMPrincipal(888888L, "other", LLMSource.QQ, "888888", hasAccount = false)
		quotaService.reserve(targetPrincipal, "req-target-1", now)

		val context = LLMToolContext(groupId = 100L, uid = "123456", name = "tester")
		val args = JsonObject().apply { addProperty("target_uid", "888888") }
		val resultJson = tool.execute(args, context)
		val result = JsonParser.parseString(resultJson).asJsonObject

		assertEquals(888888L, result.get("uid").asLong)
		assertEquals(1, result.get("used").asInt)
		assertEquals(19, result.get("remaining").asInt)
	}

	@Test
	fun `tool returns missing_uid error when context has no uid and args is empty`() = runBlocking {
		val quotaService = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 20, "Asia/Shanghai")
		val tool = GetUserQuotaTool(quotaService)

		val context = LLMToolContext(groupId = 100L, uid = null, name = "tester")
		val resultJson = tool.execute(JsonObject(), context)
		val result = JsonParser.parseString(resultJson).asJsonObject

		assertEquals("missing_uid", result.get("error").asString)
	}

	private class InMemoryQuotaStore : LLMQuotaStore {
		private val counts = mutableMapOf<String, Int>()
		private val requests = mutableMapOf<String, String>()

		@Synchronized
		override fun reserve(
			quotaKey: String,
			requestKey: String,
			limit: Int,
			expiresAtEpochSeconds: Long,
		): LLMQuotaStoreDecision? {
			val current = counts[quotaKey] ?: 0
			if (requestKey in requests) {
				return LLMQuotaStoreDecision(LLMQuotaStatus.DUPLICATE, current)
			}
			if (current >= limit) {
				return LLMQuotaStoreDecision(LLMQuotaStatus.EXCEEDED, current)
			}
			requests[requestKey] = "reserved"
			counts[quotaKey] = current + 1
			return LLMQuotaStoreDecision(LLMQuotaStatus.ACCEPTED, current + 1)
		}

		@Synchronized
		override fun refund(reservation: LLMQuotaReservation): Int? = null

		@Synchronized
		override fun used(quotaKey: String): Int? = counts[quotaKey] ?: 0
	}
}
