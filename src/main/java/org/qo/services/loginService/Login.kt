package org.qo.services.loginService

import org.qo.datas.GsonProvider.gson
import org.qo.datas.ReactiveDatabase
import org.qo.orm.LoginToken
import org.qo.orm.LoginTokenORM
import org.qo.orm.reactiveDatabase
import org.qo.orm.unsupportedSyncApi
import org.springframework.stereotype.Service
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Service
class Login {
	private var databaseOverride: ReactiveDatabase? = null
	val loginTokenORM: LoginTokenORM = LoginTokenORM()

	constructor()

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	private data class CachedLoginHistory(
		val history: List<LoginLog>,
		val expiresAt: Long,
	)

	companion object {
		private val loginHistoryCache = ConcurrentHashMap<String, CachedLoginHistory>()
		private const val loginHistoryCacheTtlMs = 30_000L
		private const val maxLoginHistoryCacheEntries = 10_000

		private fun readCachedLoginHistory(username: String): List<LoginLog>? {
			val cached = loginHistoryCache[username] ?: return null
			if (cached.expiresAt < System.currentTimeMillis()) {
				loginHistoryCache.remove(username)
				return null
			}
			return cached.history
		}

		private fun cacheLoginHistory(username: String, history: List<LoginLog>) {
			trimLoginHistoryCacheIfNeeded()
			loginHistoryCache[username] = CachedLoginHistory(
				history = history,
				expiresAt = System.currentTimeMillis() + loginHistoryCacheTtlMs,
			)
		}

		private fun trimLoginHistoryCacheIfNeeded() {
			if (loginHistoryCache.size <= maxLoginHistoryCacheEntries) return

			val currentTime = System.currentTimeMillis()
			loginHistoryCache.forEach { (username, cached) ->
				if (cached.expiresAt < currentTime) {
					loginHistoryCache.remove(username)
				}
			}

			if (loginHistoryCache.size <= maxLoginHistoryCacheEntries) return

			val overflow = loginHistoryCache.size - maxLoginHistoryCacheEntries
			loginHistoryCache.entries
				.sortedBy { it.value.expiresAt }
				.take(overflow)
				.forEach { (username, _) -> loginHistoryCache.remove(username) }
		}
	}

	@OptIn(ExperimentalEncodingApi::class)
	fun generateToken(length: Int = 64): String {
		val secureRandom = SecureRandom()
		val bytes = ByteArray(length)
		secureRandom.nextBytes(bytes)
		return Base64.encode(bytes)
	}

	fun insertInto(loginToken: String, user: String): Unit = unsupportedSyncApi("Login.insertInto")

	suspend fun insertIntoAsync(loginToken: String, user: String) {
		loginTokenORM.create(
			LoginToken(
				loginToken,
				user,
				System.currentTimeMillis() + 604800000,
			)
		)
	}

	fun insertIntoReactive(loginToken: String, user: String): Mono<Void> =
		mono { insertIntoAsync(loginToken, user) }.then()

	suspend fun validate(loginToken: String): Pair<String?, Int> {
		val result = loginTokenORM.read(loginToken) ?: return Pair(null, 1)
		if (result.expires < System.currentTimeMillis()) {
			loginTokenORM.delete(loginToken)
			return Pair(null, 3)
		}
		return Pair(result.user, 0)
	}

	fun insertLoginLog(data: String): Unit = unsupportedSyncApi("Login.insertLoginLog")

	suspend fun insertLoginLogAsync(data: String) {
		val log = gson.fromJson(data, LoginLog::class.java)
		database.execute(
			"INSERT INTO login_logs(username, time, success) VALUES (?, ?, ?)",
			listOf(log.user, log.date, log.success),
		)
		loginHistoryCache.remove(log.user)
	}

	fun insertLoginLogReactive(data: String): Mono<Void> =
		mono { insertLoginLogAsync(data) }.then()

	fun queryLoginHistory(username: String): List<LoginLog> = unsupportedSyncApi("Login.queryLoginHistory")

	suspend fun queryLoginHistoryAsync(username: String): List<LoginLog> {
		readCachedLoginHistory(username)?.let { return it }
		return database.all(
			"""
            SELECT username, time, success
            FROM login_logs
            WHERE username = ?
            ORDER BY time DESC
            LIMIT 3
        """.trimIndent(),
			listOf(username),
		) { row ->
			LoginLog(
				user = row.get("username", String::class.java).orEmpty(),
				date = org.qo.orm.longValue(row.get("time")) ?: 0L,
				success = org.qo.orm.booleanValue(row.get("success")) ?: false,
			)
		}.also {
			cacheLoginHistory(username, it)
		}
	}

	data class LoginLog(
		val user: String,
		val date: Long,
		val success: Boolean,
	)
}
