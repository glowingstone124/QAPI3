package org.qo.orm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

data class LoginToken(
	val token: String,
	val user: String,
	val expires: Long
)

class LoginTokenORM {

	private data class CachedLoginToken(
		val token: LoginToken,
		val expiresAt: Long
	)

	companion object {
		private val tokenCache = ConcurrentHashMap<String, CachedLoginToken>()
		private const val maxTokenCacheEntries = 20_000

		private fun cacheToken(token: LoginToken) {
			trimTokenCacheIfNeeded()
			tokenCache[token.token] = CachedLoginToken(token, token.expires)
		}

		private fun invalidateToken(token: String) {
			tokenCache.remove(token)
		}

		private fun readCachedToken(token: String): LoginToken? {
			val cached = tokenCache[token] ?: return null
			if (cached.expiresAt < System.currentTimeMillis()) {
				invalidateToken(token)
				return null
			}
			return cached.token
		}

		private fun trimTokenCacheIfNeeded() {
			if (tokenCache.size <= maxTokenCacheEntries) return

			val currentTime = System.currentTimeMillis()
			tokenCache.forEach { (token, cached) ->
				if (cached.expiresAt < currentTime) {
					tokenCache.remove(token)
				}
			}

			if (tokenCache.size <= maxTokenCacheEntries) return

			val overflow = tokenCache.size - maxTokenCacheEntries
			tokenCache.entries
				.sortedBy { it.value.expiresAt }
				.take(overflow)
				.forEach { (token, _) -> tokenCache.remove(token) }
		}
	}

	suspend fun create(item: LoginToken) {
		val sql = "INSERT INTO login_tokens (token, user, expires) VALUES (?, ?, ?)"
		withContext(Dispatchers.IO) {
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setString(1, item.token)
					stmt.setString(2, item.user)
					stmt.setLong(3, item.expires)
					stmt.executeUpdate()
				}
			}
		}
		cacheToken(item)
	}

	suspend fun read(token: String): LoginToken? {
		readCachedToken(token)?.let { return it }
		val sql = "SELECT token, user, expires FROM login_tokens WHERE token = ?"
		return withContext(Dispatchers.IO) {
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setString(1, token)
					val result = stmt.executeQuery()
					if (result.next()) {
						mapRowToLoginToken(result).also { cacheToken(it) }
					} else null
				}
			}
		}
	}


	suspend fun delete(token: String): Boolean {
		val sql = "DELETE FROM login_tokens WHERE token = ?"
		return withContext(Dispatchers.IO) {
			ConnectionPool.getConnection().use { connection ->
				connection.prepareStatement(sql).use { stmt ->
					stmt.setString(1, token)
					(stmt.executeUpdate() > 0).also {
						invalidateToken(token)
					}
				}
			}
		}
	}

	private fun mapRowToLoginToken(resultSet: ResultSet): LoginToken {
		return LoginToken(
			token = resultSet.getString("token"),
			user = resultSet.getString("user"),
			expires = resultSet.getLong("expires")
		)
	}
}
