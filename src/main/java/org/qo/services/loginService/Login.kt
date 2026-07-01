package org.qo.services.loginService

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import org.qo.datas.GsonProvider.gson
import org.qo.orm.LoginToken
import org.qo.orm.LoginTokenORM
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Service
class Login {
	val loginTokenORM: LoginTokenORM = LoginTokenORM()

	private data class CachedLoginHistory(
		val history: List<LoginLog>,
		val expiresAt: Long
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
				expiresAt = System.currentTimeMillis() + loginHistoryCacheTtlMs
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

	fun insertInto(loginToken: String, user: String) = runBlocking {
		loginTokenORM.create(
			LoginToken (
				loginToken,
				user,
				System.currentTimeMillis() + 604800000,
			)
		)
	}

	/**
	 * Validate a token is valid or not.
	 * @return (username,0) if success, (null, 3) if failed.
	*/
	suspend fun validate(loginToken: String): Pair<String?,Int> {
		val result = loginTokenORM.read(loginToken) ?: return Pair(null, 1)
		if (result.expires < System.currentTimeMillis()) {
			loginTokenORM.delete(loginToken)
			return Pair(null,3)
		}
		return Pair(result.user,0)
	}

	fun insertLoginLog(data: String) {
		val conn = ConnectionPool.getConnection()
		val log = gson.fromJson(data, LoginLog::class.java)
		val sql = "INSERT INTO login_logs(username, time, success) VALUES (?, ?, ?)"

		conn.use {
			it.prepareStatement(sql).use { stmt ->
				stmt.setString(1, log.user)
				stmt.setLong(2, log.date)
				stmt.setBoolean(3, log.success)
				stmt.executeUpdate()
			}
		}
		loginHistoryCache.remove(log.user)
	}


	fun queryLoginHistory(username: String): List<LoginLog> {
		readCachedLoginHistory(username)?.let { return it }
		val conn = ConnectionPool.getConnection()
		val sql = """
            SELECT username, time, success 
            FROM login_logs 
            WHERE username = ? 
            ORDER BY time DESC 
            LIMIT 3
        """

		val result = ArrayList<LoginLog>()

		conn.use {
			it.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						result.add(
							LoginLog(
								user = rs.getString("username"),
								date = rs.getLong("time"),
								success = rs.getBoolean("success")
							)
						)
					}
				}
			}
		}

		return result.also {
			cacheLoginHistory(username, it)
		}
	}
	suspend fun queryLoginHistoryAsync(username: String): List<LoginLog> = withContext(Dispatchers.IO) {
		queryLoginHistory(username)
	}



	data class LoginLog(
		val user: String,
		val date: Long,
		val success: Boolean
	)

}
