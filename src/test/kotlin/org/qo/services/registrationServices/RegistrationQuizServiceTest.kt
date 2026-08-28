package org.qo.services.registrationServices

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationQuizServiceTest {
	private lateinit var service: RegistrationQuizService
	private lateinit var quizFile: Path

	@BeforeAll
	fun setUpQuizDefinition() {
		quizFile = Files.createTempFile("qapi-registration-quiz-", ".json")
		Files.writeString(quizFile, QUIZ_DEFINITION)
		service = RegistrationQuizService(quizFile.toString())
	}

	@AfterAll
	fun removeQuizDefinition() {
		Files.deleteIfExists(quizFile)
	}

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

	private companion object {
		const val QUIZ_DEFINITION = """
{
  "passingScore": 6,
  "questions": [
    {"id": 0, "text": "Question 1", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 1, "text": "Question 2", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 2, "text": "Question 3", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 3, "text": "Question 4", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 4, "text": "Question 5", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 5, "text": "Question 6", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 6, "text": "Question 7", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 7, "text": "Question 8", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 8, "text": "Question 9", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30},
    {"id": 9, "text": "Question 10", "options": ["A", "B", "C", "D"], "timeLimitSeconds": 30}
  ],
  "answers": [1, 2, 0, 2, 1, 0, 3, 2, 2, 3]
}
"""
	}
}
