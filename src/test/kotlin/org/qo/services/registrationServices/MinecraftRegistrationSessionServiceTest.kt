package org.qo.services.registrationServices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
		assertNull(service.complete(pending.id, "Alex_123", 7, true))
		assertNull(service.claim("Alex_123", 7))
	}
}
