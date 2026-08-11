package org.qo.services.registrationServices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftRegistrationSessionServiceTest {
	private val service = MinecraftRegistrationSessionService()

	@Test
	fun `claimed session can be resumed by the same node only`() {
		val pending = service.create("Alex_123", 123456)

		val claimed = assertNotNull(service.claim("alex_123", 7))
		assertEquals(pending.id, claimed.id)
		assertEquals(MinecraftRegistrationSessionState.CLAIMED, claimed.state)
		assertEquals(claimed.id, assertNotNull(service.claim("Alex_123", 7)).id)
		assertNull(service.claim("Alex_123", 8))
	}

	@Test
	fun `only the claiming node can complete a session`() {
		val pending = service.create("Alex_123", 123456)
		assertNotNull(service.claim("Alex_123", 7))

		assertNull(service.complete(pending.id, "Alex_123", 8, true))
		val completed = assertNotNull(service.complete(pending.id, "alex_123", 7, true))
		assertEquals(MinecraftRegistrationSessionState.COMPLETED, completed.state)
		assertEquals(true, completed.passed)
		assertEquals(completed, service.complete(pending.id, "Alex_123", 7, true))
		assertNull(service.complete(pending.id, "Alex_123", 7, false))
		assertNull(service.claim("Alex_123", 7))
	}

	@Test
	fun `passed session is a one-time proof bound to name and uid`() {
		val pending = service.create("Alex_123", 123456)
		assertNotNull(service.claim("Alex_123", 7))
		assertNotNull(service.complete(pending.id, "Alex_123", 7, true))

		assertFalse(service.consumePassed(pending.id, "Steve_123", 123456))
		assertFalse(service.consumePassed(pending.id, "Alex_123", 999999))
		assertTrue(service.consumePassed(pending.id, "alex_123", 123456))
		assertFalse(service.consumePassed(pending.id, "Alex_123", 123456))
		assertNull(service.status(pending.id, "Alex_123", 123456))
	}

	@Test
	fun `failed session cannot be consumed`() {
		val pending = service.create("Alex_123", 123456)
		assertNotNull(service.claim("Alex_123", 7))
		assertNotNull(service.complete(pending.id, "Alex_123", 7, false))

		assertFalse(service.consumePassed(pending.id, "Alex_123", 123456))
	}
}
