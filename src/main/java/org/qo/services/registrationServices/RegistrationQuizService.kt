package org.qo.services.registrationServices

import com.google.gson.JsonParser
import org.qo.utils.Funcs
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RegistrationQuizQuestion(
	val id: Int,
	val text: String,
	val options: List<String>,
	val timeLimitSeconds: Int,
	internal val correctOption: Int
)

data class RegistrationQuizSession(
	val id: String,
	val name: String,
	val uid: Long,
	val expiresAt: Long,
	val passingScore: Int,
	val questions: List<RegistrationQuizQuestion>
)

sealed interface RegistrationQuizStartResult {
	data class Started(val session: RegistrationQuizSession) : RegistrationQuizStartResult
	data object InvalidUsername : RegistrationQuizStartResult
	data object InvalidUid : RegistrationQuizStartResult
	data class CapacityReached(
		val activeSessions: Int,
		val limit: Int
	) : RegistrationQuizStartResult
}

data class RegistrationQuizResult(
	val passed: Boolean,
	val score: Int,
	val questionCount: Int,
	val passingScore: Int,
	val verificationToken: String?
)

data class RegistrationQuizProof(
	val name: String,
	val uid: Long,
	val score: Int,
	val expiresAt: Long
)

data class RegistrationQuizMetadata(
	val questionCount: Int,
	val passingScore: Int
)

@Service
class RegistrationQuizService(
	@param:Value("\${qapi.registration.quiz-file:quiz.json}")
	private val quizFileName: String,
) {
	private val sessions = ConcurrentHashMap<String, RegistrationQuizSession>()
	private val proofs = ConcurrentHashMap<String, RegistrationQuizProof>()

	fun metadata(): RegistrationQuizMetadata {
		val definition = loadDefinition()
		return RegistrationQuizMetadata(
			questionCount = definition.questions.size,
			passingScore = definition.passingScore
		)
	}

	@Synchronized
	fun start(name: String, uid: Long): RegistrationQuizStartResult {
		cleanup()
		if (!USERNAME.matches(name)) return RegistrationQuizStartResult.InvalidUsername
		if (uid <= 0) return RegistrationQuizStartResult.InvalidUid
		sessions.entries.removeIf {
			it.value.name.equals(name, ignoreCase = true) || it.value.uid == uid
		}
		if (sessions.size >= MAX_ACTIVE_SESSIONS) {
			return RegistrationQuizStartResult.CapacityReached(sessions.size, MAX_ACTIVE_SESSIONS)
		}
		val definition = loadDefinition()
		val session = RegistrationQuizSession(
			id = UUID.randomUUID().toString(),
			name = name,
			uid = uid,
			expiresAt = System.currentTimeMillis() + QUIZ_SESSION_TTL_MILLIS,
			passingScore = definition.passingScore,
			questions = definition.questions
		)
		sessions[session.id] = session
		return RegistrationQuizStartResult.Started(session)
	}

	fun submit(sessionId: String, name: String, uid: Long, answers: List<Int>): RegistrationQuizResult? {
		cleanup()
		val session = sessions.remove(sessionId) ?: return null
		if (session.expiresAt <= System.currentTimeMillis()
			|| !session.name.equals(name, ignoreCase = true)
			|| session.uid != uid
			|| answers.size != session.questions.size
			|| answers.any { it !in -1..3 }
		) return null

		val score = session.questions.indices.count { answers[it] == session.questions[it].correctOption }
		if (score < session.passingScore) {
			return RegistrationQuizResult(
				passed = false,
				score = score,
				questionCount = session.questions.size,
				passingScore = session.passingScore,
				verificationToken = null
			)
		}

		val token = Funcs.generateRandomString(48)
		proofs[token] = RegistrationQuizProof(
			name = session.name,
			uid = session.uid,
			score = score,
			expiresAt = System.currentTimeMillis() + REGISTRATION_PROOF_TTL_MILLIS
		)
		return RegistrationQuizResult(
			passed = true,
			score = score,
			questionCount = session.questions.size,
			passingScore = session.passingScore,
			verificationToken = token
		)
	}

	fun consumeProof(token: String?, name: String?, uid: Long?): RegistrationQuizProof? {
		cleanup()
		if (token.isNullOrBlank() || name == null || uid == null) return null
		val proof = proofs.remove(token) ?: return null
		if (proof.expiresAt <= System.currentTimeMillis()
			|| !proof.name.equals(name, ignoreCase = true)
			|| proof.uid != uid
		) return null
		return proof
	}

	private fun cleanup() {
		val now = System.currentTimeMillis()
		sessions.entries.removeIf { it.value.expiresAt <= now }
		proofs.entries.removeIf { it.value.expiresAt <= now }
	}

	private fun loadDefinition(): RegistrationQuizDefinition {
		val file = File(quizFileName)
		check(file.isFile) { "Missing registration quiz configuration: ${file.absolutePath}" }
		val root = file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
		val passingScore = root.get("passingScore")?.asInt
			?: error("quiz.json is missing passingScore")
		val questionArray = root.getAsJsonArray("questions")
			?: error("quiz.json is missing questions")
		val answerArray = root.getAsJsonArray("answers")
			?: error("quiz.json is missing answers")
		check(questionArray.size() == answerArray.size() && questionArray.size() > 0) {
			"quiz.json questions and answers must have the same non-zero size"
		}
		val questions = questionArray.mapIndexed { index, element ->
			val question = element.asJsonObject
			val options = question.getAsJsonArray("options")?.map { it.asString }
				?: error("quiz.json question $index is missing options")
			check(options.size == 4) { "quiz.json question $index must have exactly four options" }
			val correctOption = answerArray[index].asInt
			check(correctOption in options.indices) { "quiz.json answer $index is outside the option range" }
			RegistrationQuizQuestion(
				id = question.get("id")?.asInt ?: index,
				text = question.get("text")?.asString?.takeIf { it.isNotBlank() }
					?: error("quiz.json question $index is missing text"),
				options = options,
				timeLimitSeconds = question.get("timeLimitSeconds")?.asInt?.takeIf { it > 0 }
					?: error("quiz.json question $index has an invalid timeLimitSeconds"),
				correctOption = correctOption
			)
		}
		check(passingScore in 1..questions.size) {
			"quiz.json passingScore must be between 1 and ${questions.size}"
		}
		return RegistrationQuizDefinition(passingScore, questions)
	}

	companion object {
		const val QUIZ_FILE_NAME = "quiz.json"
		const val QUIZ_SESSION_TTL_MILLIS = 15 * 60 * 1000L
		const val REGISTRATION_PROOF_TTL_MILLIS = 10 * 60 * 1000L
		const val MAX_ACTIVE_SESSIONS = 1_000
		private val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
	}
}

private data class RegistrationQuizDefinition(
	val passingScore: Int,
	val questions: List<RegistrationQuizQuestion>
)

data class RegistrationQuizSessionRequest(
	val name: String?,
	val uid: Long?
)

data class RegistrationQuizSubmissionRequest(
	val sessionId: String?,
	val name: String?,
	val uid: Long?,
	val answers: List<Int>?
)
