package org.qo.services.registrationServices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class RegistrationQuizServiceTest {
	private val service = RegistrationQuizService()

	@Test
	fun `server calculates score and issues a single use bound proof`() {
		val session = startSession("Alex_123", 123456)
		val result = assertNotNull(service.submit(session.id, "Alex_123", 123456, listOf(1, 2, 0, 2, 1, 0, 3, 2, 2, 3)))

		assertTrue(result.passed)
		assertEquals(10, result.score)
		assertEquals(10, result.questionCount)
		assertEquals(6, result.passingScore)
		val token = assertNotNull(result.verificationToken)
		assertNull(service.consumeProof(token, "Other", 123456))
		assertNull(service.consumeProof(token, "Alex_123", 123456))
	}

	@Test
	fun `failed quiz never creates a registration proof`() {
		val session = startSession("Alex_123", 123456)
		val result = assertNotNull(service.submit(session.id, "Alex_123", 123456, List(10) { -1 }))

		assertFalse(result.passed)
		assertEquals(0, result.score)
		assertNull(result.verificationToken)
	}

	@Test
	fun `passing proof can be consumed exactly once`() {
		val session = startSession("Alex_123", 123456)
		val result = assertNotNull(service.submit(session.id, "Alex_123", 123456, listOf(1, 2, 0, 2, 1, 0, 3, 2, 2, 3)))
		val token = assertNotNull(result.verificationToken)

		assertNotNull(service.consumeProof(token, "alex_123", 123456))
		assertNull(service.consumeProof(token, "Alex_123", 123456))
	}

	@Test
	fun `start reports the invalid input field`() {
		assertIs<RegistrationQuizStartResult.InvalidUsername>(service.start("a!", 123456))
		assertIs<RegistrationQuizStartResult.InvalidUid>(service.start("Alex_123", 0))
	}

	private fun startSession(name: String, uid: Long): RegistrationQuizSession =
		assertIs<RegistrationQuizStartResult.Started>(service.start(name, uid)).session
}
