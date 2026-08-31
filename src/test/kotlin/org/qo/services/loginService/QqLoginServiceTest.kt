package org.qo.services.loginService

import org.qo.redis.Configuration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QqLoginServiceTest {
	private var previousRedisState = true

	@BeforeTest
	fun disableRedis() {
		previousRedisState = Configuration.EnableRedis
		Configuration.EnableRedis = false
	}

	@AfterTest
	fun restoreRedis() {
		Configuration.EnableRedis = previousRedisState
	}

	@Test
	fun `generated codes contain letters and digits`() {
		repeat(200) {
			val code = QqLoginCodeGenerator.generate()
			assertTrue(code.matches(Regex("[A-Z0-9]{8}")))
			assertTrue(code.any(Char::isLetter))
			assertTrue(code.any(Char::isDigit))
		}
	}

	@Test
	fun `challenge code can only be claimed once`() {
		val store = QqLoginChallengeStore()
		val record = QqLoginChallengeRecord(
			requestId = "12345678-1234-1234-1234-123456789abc",
			code = "A1B2C3D4",
			qq = 123456789L,
			expiresAt = System.currentTimeMillis() + 60_000L,
		)

		assertTrue(store.create(record))
		assertNotNull(store.findRequest(record.requestId))
		assertEquals(record.requestId, store.findRequestIdByCode(record.code))
		assertTrue(store.claimCode(record.code, record.requestId))
		assertFalse(store.claimCode(record.code, record.requestId))
	}
}
