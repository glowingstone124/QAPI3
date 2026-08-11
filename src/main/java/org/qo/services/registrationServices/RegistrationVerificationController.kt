package org.qo.services.registrationServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.qo.datas.Nodes
import org.qo.datas.Role
import org.qo.utils.AuthTokens
import org.qo.utils.ReturnInterface
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/registration")
class RegistrationVerificationController(
	private val quizService: RegistrationQuizService,
	private val minecraftSessionService: MinecraftRegistrationSessionService,
	private val nodes: Nodes,
	private val ri: ReturnInterface,
	@Value("\${qapi.registration.chambers-node-names:chambers}") chamberNodeNames: String,
	@param:Value("\${qapi.registration.chambers-address:qoriginal.vip}") private val chambersAddress: String,
	@param:Value("\${qapi.registration.chambers-enabled:false}") private val chambersEnabled: Boolean,
) {
	private val allowedChambersNodeNames = chamberNodeNames.split(",")
		.map { it.trim().lowercase() }
		.filter { it.isNotEmpty() }
		.toSet()

	@GetMapping("/verification-methods")
	fun methods(): ResponseEntity<String> {
		val quizMetadata = runCatching { quizService.metadata() }.getOrNull()
		val methods = JsonArray()
		RegistrationVerificationMethod.entries.forEach { method ->
			val available = method.available &&
				(method != RegistrationVerificationMethod.QUIZ || quizMetadata != null) &&
				(method != RegistrationVerificationMethod.MINECRAFT || chambersEnabled)
			methods.add(JsonObject().apply {
				addProperty("id", method.id)
				addProperty("displayName", method.displayName)
				addProperty("description", method.description)
				addProperty("available", available)
				addProperty("legacy", method.legacy)
				addProperty(
					"state",
					if (available) "available"
					else if (method == RegistrationVerificationMethod.MINECRAFT) "reserved"
					else "unavailable"
				)
				if (method == RegistrationVerificationMethod.QUIZ && quizMetadata != null) {
					addProperty("questionCount", quizMetadata.questionCount)
					addProperty("passingScore", quizMetadata.passingScore)
				}
				if (method == RegistrationVerificationMethod.MINECRAFT) {
					addProperty("serverAddress", chambersAddress.trim())
				}
			})
		}
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("defaultMethod", RegistrationVerificationMethod.QUIZ.id)
			add("methods", methods)
		}.toString())
	}

	@PostMapping("/quiz/session")
	fun startQuiz(
		@RequestBody request: RegistrationQuizSessionRequest
	): ResponseEntity<String> {
		val name = request.name
		val uid = request.uid
		if (name == null) {
			return response("missing_minecraft_username", "缺少 Minecraft 用户名。", HttpStatus.BAD_REQUEST) {
				addProperty("field", "name")
			}
		}
		if (uid == null) {
			return response("missing_qq_uid", "缺少 QQ 号。", HttpStatus.BAD_REQUEST) {
				addProperty("field", "uid")
			}
		}
		val startResult = runCatching { quizService.start(name, uid) }.getOrElse {
			return response("quiz_configuration_unavailable", "答题配置暂时不可用，请联系管理员。", HttpStatus.SERVICE_UNAVAILABLE)
		}
		val session = when (startResult) {
			is RegistrationQuizStartResult.Started -> startResult.session
			RegistrationQuizStartResult.InvalidUsername -> return response(
				"invalid_minecraft_username",
				"Minecraft 用户名格式无效：应为 3–16 位，且只能包含英文字母、数字和下划线。",
				HttpStatus.BAD_REQUEST
			) {
				addProperty("field", "name")
				addProperty("requirement", "3-16 characters: A-Z, a-z, 0-9, underscore")
			}
			RegistrationQuizStartResult.InvalidUid -> return response(
				"invalid_qq_uid",
				"QQ 号无效：必须是大于 0 的整数。",
				HttpStatus.BAD_REQUEST
			) {
				addProperty("field", "uid")
				addProperty("minimum", 1)
			}
			is RegistrationQuizStartResult.CapacityReached -> return response(
				"quiz_session_capacity_reached",
				"当前有效答题会话已达上限，请等待已有会话过期后重试。",
				HttpStatus.TOO_MANY_REQUESTS
			) {
				addProperty("activeSessions", startResult.activeSessions)
				addProperty("limit", startResult.limit)
				addProperty("sessionTtlSeconds", RegistrationQuizService.QUIZ_SESSION_TTL_MILLIS / 1_000)
			}
		}
		val questions = JsonArray()
		session.questions.forEach { question ->
			questions.add(JsonObject().apply {
				addProperty("id", question.id)
				addProperty("text", question.text)
				addProperty("timeLimitSeconds", question.timeLimitSeconds)
				add("options", JsonArray().apply { question.options.forEach(::add) })
			})
		}
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("sessionId", session.id)
			addProperty("expiresAt", session.expiresAt)
			addProperty("questionCount", session.questions.size)
			addProperty("passingScore", session.passingScore)
			add("questions", questions)
		}.toString())
	}

	@PostMapping("/quiz/submit")
	fun submitQuiz(
		@RequestBody request: RegistrationQuizSubmissionRequest
	): ResponseEntity<String> {
		val sessionId = request.sessionId
		val name = request.name
		val uid = request.uid
		val answers = request.answers
		if (sessionId.isNullOrBlank() || name == null || uid == null || answers == null) {
			return response("invalid_quiz_submission", "答题提交格式无效。", HttpStatus.BAD_REQUEST)
		}
		val result = quizService.submit(sessionId, name, uid, answers)
			?: return response("invalid_quiz_session", "答题会话无效、已过期或已使用。", HttpStatus.GONE)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("passed", result.passed)
			addProperty("score", result.score)
			addProperty("questionCount", result.questionCount)
			addProperty("passingScore", result.passingScore)
			result.verificationToken?.let { addProperty("verificationToken", it) }
		}.toString())
	}

	@PostMapping("/minecraft/session")
	fun startMinecraftSession(
		@RequestBody request: MinecraftVerificationSessionRequest
	): ResponseEntity<String> {
		chambersUnavailable()?.let { return it }
		if (!isUsername(request.name) || request.uid == null || request.uid <= 0) {
			return response("invalid_registration_data", "Minecraft 用户名或 QQ 号无效。", HttpStatus.BAD_REQUEST)
		}
		val session = minecraftSessionService.create(request.name!!, request.uid)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("sessionId", session.id)
			addProperty("expiresAt", session.expiresAt)
			addProperty("state", "pending")
		}.toString())
	}

	@PostMapping("/minecraft/claim")
	fun claimMinecraftSession(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody request: MinecraftVerificationClaimRequest
	): ResponseEntity<String> {
		chambersUnavailable()?.let { return it }
		val nodeId = authenticateChambersNode(token, authorization)
			?: return response("unauthorized", "仅测试服务器可以领取 Minecraft 测试请求。", HttpStatus.UNAUTHORIZED)
		val name = request.name
		if (!isUsername(name)) {
			return response("invalid_registration_data", "Minecraft 用户名无效。", HttpStatus.BAD_REQUEST)
		}
		val session = minecraftSessionService.claim(name!!, nodeId)
			?: return response(
				"minecraft_test_not_pending",
				"没有待处理的 Minecraft 测试请求，或请求已过期、已被领取。",
				HttpStatus.NOT_FOUND
			)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("sessionId", session.id)
			addProperty("name", session.name)
			addProperty("expiresAt", session.expiresAt)
			addProperty("state", "claimed")
		}.toString())
	}

	@PostMapping("/minecraft/status")
	fun minecraftSessionStatus(
		@RequestBody request: MinecraftVerificationStatusRequest
	): ResponseEntity<String> {
		chambersUnavailable()?.let { return it }
		val sessionId = request.sessionId
		val name = request.name
		val uid = request.uid
		if (sessionId.isNullOrBlank() || !isUsername(name) || uid == null || uid <= 0) {
			return response("invalid_registration_data", "Minecraft 测试会话查询格式无效。", HttpStatus.BAD_REQUEST)
		}
		val session = minecraftSessionService.status(sessionId, name!!, uid)
			?: return response(
				"invalid_minecraft_session",
				"Minecraft 测试会话无效或已过期。",
				HttpStatus.GONE
			)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("sessionId", session.id)
			addProperty("expiresAt", session.expiresAt)
			addProperty("state", session.state.name.lowercase())
			if (session.state == MinecraftRegistrationSessionState.COMPLETED) {
				addProperty("passed", session.passed == true)
				if (session.passed == true) addProperty("verificationToken", session.id)
			}
		}.toString())
	}

	@PostMapping("/minecraft/result")
	fun submitMinecraftResult(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody request: MinecraftVerificationResultRequest
	): ResponseEntity<String> {
		chambersUnavailable()?.let { return it }
		val nodeId = authenticateChambersNode(token, authorization)
			?: return response("unauthorized", "仅测试服务器可以提交 Minecraft 测试结果。", HttpStatus.UNAUTHORIZED)
		if (request.sessionId.isNullOrBlank() || !isUsername(request.name) || request.passed == null) {
			return response("invalid_result", "Minecraft 测试结果格式无效。", HttpStatus.BAD_REQUEST)
		}
		val completed = minecraftSessionService.complete(
			request.sessionId,
			request.name!!,
			nodeId,
			request.passed
		) ?: return response(
			"invalid_minecraft_session",
			"Minecraft 测试会话无效、未被当前服务器领取或已完成。",
			HttpStatus.GONE
		)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("sessionId", completed.id)
			addProperty("state", "completed")
			addProperty("passed", completed.passed)
		}.toString())
	}

	private fun authenticateChambersNode(token: String?, authorization: String?): Int? {
		val resolved = AuthTokens.resolve(token, authorization) ?: return null
		val node = nodes.getNodeFromToken(resolved) ?: return null
		if (node.role != Role.SERVER || node.name.lowercase() !in allowedChambersNodeNames) return null
		return node.id
	}

	private fun chambersUnavailable(): ResponseEntity<String>? =
		if (chambersEnabled) null
		else response(
			"minecraft_verification_unavailable",
			"Chamber 世界测试暂未开放。",
			HttpStatus.SERVICE_UNAVAILABLE
		)

	private fun isUsername(value: String?): Boolean =
		value != null && USERNAME.matches(value)

	private fun response(
		code: String,
		message: String,
		status: HttpStatus,
		details: JsonObject.() -> Unit = {}
	): ResponseEntity<String> =
		ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", code)
			addProperty("message", message)
			addProperty("state", if (status == HttpStatus.NOT_IMPLEMENTED) "reserved" else "error")
			details()
		}.toString(), status)

	private companion object {
		val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
	}
}
