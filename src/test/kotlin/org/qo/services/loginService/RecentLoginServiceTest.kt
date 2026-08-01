package org.qo.services.loginService

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentLoginServiceTest {
	private val store = InMemoryRecentLoginStore()
	private val service = RecentLoginService(store)

	@Test
	fun `same IP is eligible for 60 seconds after a successful login`() {
		assertTrue(service.recordSuccessfulLogin("Alex", "203.0.113.8", nowMillis = 10_000L))

		assertTrue(service.canAutoLogin("alex", "203.0.113.8", nowMillis = 69_999L))
		assertEquals(60L, store.lastExpirySeconds)
	}

	@Test
	fun `different IP is rejected`() {
		service.recordSuccessfulLogin("alex", "203.0.113.8", nowMillis = 10_000L)

		assertFalse(service.canAutoLogin("alex", "203.0.113.9", nowMillis = 20_000L))
	}

	@Test
	fun `login at least 60 seconds old is rejected even if Redis still has the value`() {
		service.recordSuccessfulLogin("alex", "203.0.113.8", nowMillis = 10_000L)

		assertFalse(service.canAutoLogin("alex", "203.0.113.8", nowMillis = 70_000L))
	}

	@Test
	fun `future timestamp and legacy IP-only value are rejected`() {
		service.recordSuccessfulLogin("alex", "203.0.113.8", nowMillis = 20_000L)
		assertFalse(service.canAutoLogin("alex", "203.0.113.8", nowMillis = 19_999L))

		store.values["recent_game_login:alex"] = "203.0.113.8"
		assertFalse(service.canAutoLogin("alex", "203.0.113.8", nowMillis = 20_000L))
	}

	@Test
	fun `hostnames and malformed addresses are not recorded`() {
		assertFalse(service.recordSuccessfulLogin("alex", "example.com", nowMillis = 10_000L))
		assertFalse(service.recordSuccessfulLogin("alex", "999.0.0.1", nowMillis = 10_000L))
		assertTrue(store.values.isEmpty())
	}

	private class InMemoryRecentLoginStore : RecentLoginStore {
		val values = mutableMapOf<String, String>()
		var lastExpirySeconds: Long? = null

		override fun put(key: String, value: String, expiresSeconds: Long): Boolean {
			values[key] = value
			lastExpirySeconds = expiresSeconds
			return true
		}

		override fun get(key: String): String? = values[key]
	}
}
