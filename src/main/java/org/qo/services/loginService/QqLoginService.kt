package org.qo.services.loginService

import com.google.gson.Gson
import org.qo.orm.UserORM
import org.qo.redis.Configuration
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.llmServices.LLMDailyQuotaService
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class QqLoginChallengeRecord(
	val requestId: String,
	val code: String,
	val qq: Long,
	val expiresAt: Long,
	val status: String = "pending",
	val token: String? = null,
	val username: String? = null,
	val accountType: String? = null,
	val dailyLimit: Int? = null,
)

sealed interface QqLoginConfirmation {
	data class Authorized(val accountType: String, val dailyLimit: Int) : QqLoginConfirmation
	data object NotFound : QqLoginConfirmation
	data object QqMismatch : QqLoginConfirmation
	data object Expired : QqLoginConfirmation
	data object AlreadyUsed : QqLoginConfirmation
	data object AccountFrozen : QqLoginConfirmation
}

internal object QqLoginCodeGenerator {
	private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
	private const val DIGITS = "23456789"
	private val random = SecureRandom()

	fun generate(): String {
		val chars = mutableListOf<Char>()
		chars += LETTERS[random.nextInt(LETTERS.length)]
		chars += DIGITS[random.nextInt(DIGITS.length)]
		repeat(6) {
			val alphabet = LETTERS + DIGITS
			chars += alphabet[random.nextInt(alphabet.length)]
		}
		for (index in chars.lastIndex downTo 1) {
			val target = random.nextInt(index + 1)
			val value = chars[index]
			chars[index] = chars[target]
			chars[target] = value
		}
		return chars.joinToString("")
	}
}

@Service
class QqLoginChallengeStore {
	private val gson = Gson()
	private val redis = Redis()
	private val database = DatabaseType.QO_TEMP_DATABASE.value
	private val localRequests = ConcurrentHashMap<String, QqLoginChallengeRecord>()
	private val localCodes = ConcurrentHashMap<String, String>()

	fun create(record: QqLoginChallengeRecord): Boolean {
		cleanupExpired()
		if (localCodes.putIfAbsent(record.code, record.requestId) != null) return false
		localRequests[record.requestId] = record
		if (!Configuration.EnableRedis) return true
		val ttl = remainingSeconds(record)
		val inserted = redis.setIfAbsentWithExpire(codeKey(record.code), record.requestId, database, ttl)
			.onException { println("QQ login code store failed: ${it.message}") } == true
		if (!inserted) {
			localCodes.remove(record.code, record.requestId)
			localRequests.remove(record.requestId)
			return false
		}
		val requestStored = redis.insert(requestKey(record.requestId), gson.toJson(record), database, ttl)
			.onException { println("QQ login request store failed: ${it.message}") } != null
		if (!requestStored) {
			redis.delete(codeKey(record.code), database).ignoreException()
			localCodes.remove(record.code, record.requestId)
			localRequests.remove(record.requestId)
			return false
		}
		return true
	}

	fun findRequest(requestId: String): QqLoginChallengeRecord? {
		cleanupExpired()
		if (Configuration.EnableRedis) {
			redis.get(requestKey(requestId), database).onException {
				println("QQ login request read failed: ${it.message}")
			}?.let { json -> return runCatching { gson.fromJson(json, QqLoginChallengeRecord::class.java) }.getOrNull() }
		}
		return localRequests[requestId]?.takeUnless(::isExpired)
	}

	fun findRequestIdByCode(code: String): String? {
		cleanupExpired()
		if (Configuration.EnableRedis) {
			redis.get(codeKey(code), database).onException {
				println("QQ login code read failed: ${it.message}")
			}?.let { return it }
		}
		return localCodes[code]
	}

	fun claimCode(code: String, expectedRequestId: String): Boolean {
		val localClaimed = localCodes.remove(code, expectedRequestId)
		if (!Configuration.EnableRedis) return localClaimed
		val claimed = redis.getAndDelete(codeKey(code), database).onException {
			println("QQ login code claim failed: ${it.message}")
		}
		return claimed == expectedRequestId
	}

	fun update(record: QqLoginChallengeRecord) {
		localRequests[record.requestId] = record
		if (Configuration.EnableRedis) {
			redis.insert(requestKey(record.requestId), gson.toJson(record), database, remainingSeconds(record))
				.onException { println("QQ login request update failed: ${it.message}") }
		}
	}

	private fun cleanupExpired() {
		val now = System.currentTimeMillis()
		localRequests.entries.removeIf { (_, record) ->
			if (record.expiresAt > now) return@removeIf false
			localCodes.remove(record.code, record.requestId)
			true
		}
	}

	private fun remainingSeconds(record: QqLoginChallengeRecord): Long =
		((record.expiresAt - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(1L)

	private fun isExpired(record: QqLoginChallengeRecord): Boolean = record.expiresAt <= System.currentTimeMillis()
	private fun requestKey(requestId: String) = "kotshi:qq-login:request:$requestId"
	private fun codeKey(code: String) = "kotshi:qq-login:code:$code"
}

@Service
class QqLoginService(
	private val store: QqLoginChallengeStore,
	private val login: Login,
	private val dailyQuotaService: LLMDailyQuotaService,
) {
	private val userORM = UserORM()

	fun start(qq: Long): QqLoginChallengeRecord {
		require(qq in MIN_QQ..MAX_QQ) { "invalid QQ number" }
		repeat(12) {
			val now = System.currentTimeMillis()
			val record = QqLoginChallengeRecord(
				requestId = UUID.randomUUID().toString(),
				code = QqLoginCodeGenerator.generate(),
				qq = qq,
				expiresAt = now + CHALLENGE_TTL_MILLIS,
			)
			if (store.create(record)) return record
		}
		error("unable to allocate a unique QQ login code")
	}

	fun status(requestId: String): QqLoginChallengeRecord? =
		requestId.takeIf { it.matches(REQUEST_ID_PATTERN) }?.let(store::findRequest)

	suspend fun confirm(qq: Long, rawCode: String): QqLoginConfirmation {
		if (qq !in MIN_QQ..MAX_QQ) return QqLoginConfirmation.QqMismatch
		val code = rawCode.trim().uppercase()
		if (!code.matches(CODE_PATTERN)) return QqLoginConfirmation.NotFound
		val requestId = store.findRequestIdByCode(code) ?: return QqLoginConfirmation.NotFound
		val record = store.findRequest(requestId) ?: return QqLoginConfirmation.Expired
		if (record.qq != qq) return QqLoginConfirmation.QqMismatch
		if (record.expiresAt <= System.currentTimeMillis()) return QqLoginConfirmation.Expired
		if (record.status != "pending") return QqLoginConfirmation.AlreadyUsed
		if (!store.claimCode(code, requestId)) return QqLoginConfirmation.AlreadyUsed

		val account = userORM.readAsync(qq)
		if (account?.frozen == true) return QqLoginConfirmation.AccountFrozen
		val hasAccount = account != null
		val token = login.generateToken(32)
		login.insertIntoAsync(token, account?.username ?: "$GUEST_PREFIX$qq")
		val accountType = if (hasAccount) "qo" else "guest"
		val dailyLimit = dailyQuotaService.effectiveLimit(hasAccount)
		store.update(
			record.copy(
				status = "authorized",
				token = token,
				username = account?.username ?: "QQ $qq",
				accountType = accountType,
				dailyLimit = dailyLimit,
			),
		)
		return QqLoginConfirmation.Authorized(accountType, dailyLimit)
	}

	companion object {
		const val GUEST_PREFIX = "qq:"
		private const val CHALLENGE_TTL_MILLIS = 15 * 60 * 1000L
		private const val MIN_QQ = 10_000L
		private const val MAX_QQ = 999_999_999_999L
		private val CODE_PATTERN = Regex("[A-Z0-9]{8}")
		private val REQUEST_ID_PATTERN = Regex("[a-fA-F0-9-]{36}")
	}
}
