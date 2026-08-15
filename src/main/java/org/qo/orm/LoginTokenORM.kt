package org.qo.orm

import org.qo.datas.ReactiveDatabase
import java.util.concurrent.ConcurrentHashMap

data class LoginToken(
	val token: String,
	val user: String,
	val expires: Long,
)

class LoginTokenORM() {
	private var databaseOverride: ReactiveDatabase? = null

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	private data class CachedLoginToken(
		val token: LoginToken,
		val expiresAt: Long,
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
		database.execute(
			"INSERT INTO login_tokens (token, user, expires) VALUES (?, ?, ?)",
			listOf(item.token, item.user, item.expires),
		)
		cacheToken(item)
	}

	suspend fun read(token: String): LoginToken? {
		readCachedToken(token)?.let { return it }
		return database.one(
			"SELECT token, user, expires FROM login_tokens WHERE token = ?",
			listOf(token),
		) { row ->
			LoginToken(
				token = row.get("token", String::class.java).orEmpty(),
				user = row.get("user", String::class.java).orEmpty(),
				expires = longValue(row.get("expires")) ?: 0L,
			)
		}?.also(::cacheToken)
	}

	suspend fun delete(token: String): Boolean {
		val deleted = database.execute(
			"DELETE FROM login_tokens WHERE token = ?",
			listOf(token),
		) > 0
		if (deleted) {
			invalidateToken(token)
		}
		return deleted
	}
}
