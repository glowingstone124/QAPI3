package org.qo.orm

import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CancellationException
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.SchemaInitializer
import java.util.concurrent.ConcurrentHashMap

data class LoginToken(
	val token: String,
	val user: String,
	val expires: Long,
	val clientId: String = "game",
)

class LoginTokenORM() {
	private var databaseOverride: ReactiveDatabase? = null

	constructor(database: ReactiveDatabase) : this() {
		this.databaseOverride = database
	}

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)
	private val schema = SchemaInitializer()

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
		ensureClientIdColumn()
		database.execute(
			"INSERT INTO login_tokens (token, `user`, expires, client_id) VALUES (?, ?, ?, ?)",
			listOf(item.token, item.user, item.expires, item.clientId),
		)
		cacheToken(item)
	}

	suspend fun read(token: String): LoginToken? {
		ensureClientIdColumn()
		readCachedToken(token)?.let { return it }
		return database.one(
			"SELECT token, `user`, expires, client_id FROM login_tokens WHERE token = ?",
			listOf(token),
		) { row ->
			LoginToken(
				token = row.get("token", String::class.java).orEmpty(),
				user = row.get("user", String::class.java).orEmpty(),
				expires = longValue(row.get("expires")) ?: 0L,
				clientId = row.get("client_id", String::class.java) ?: "game",
			)
		}?.also(::cacheToken)
	}

	suspend fun delete(token: String): Boolean {
		ensureClientIdColumn()
		val deleted = database.execute(
			"DELETE FROM login_tokens WHERE token = ?",
			listOf(token),
		) > 0
		if (deleted) {
			invalidateToken(token)
		}
		return deleted
	}

	suspend fun extendExpiry(token: String, expiresAt: Long, now: Long): LoginToken? {
		ensureClientIdColumn()
		val current = read(token) ?: return null
		if (current.expires < now) {
			delete(token)
			return null
		}

		val updated = database.execute(
			"UPDATE login_tokens SET expires = ? WHERE token = ? AND expires >= ?",
			listOf(expiresAt, token, now),
		)
		if (updated <= 0) {
			invalidateToken(token)
			return null
		}

		return current.copy(expires = expiresAt).also(::cacheToken)
	}

	private suspend fun ensureClientIdColumn() = schema.ensure {
		if (clientIdColumnExists()) return@ensure
		try {
			database.execute(
				"ALTER TABLE login_tokens ADD COLUMN client_id VARCHAR(32) NOT NULL DEFAULT 'game'",
			)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			if (isDuplicateClientIdColumn(error) && clientIdColumnExists()) return@ensure
			throw IllegalStateException("Login token client ID migration failed", error)
		}
	}

	private suspend fun clientIdColumnExists(): Boolean = database.one(
		"""
		SELECT 1 FROM information_schema.columns
		WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
		  AND LOWER(table_name) = 'login_tokens'
		  AND LOWER(column_name) = 'client_id'
		LIMIT 1
		""".trimIndent(),
	) { true } != null

	private fun isDuplicateClientIdColumn(error: Throwable): Boolean =
		generateSequence(error) { it.cause }
			.filterIsInstance<R2dbcException>()
			.any { it.errorCode == 1060 || it.sqlState == "42S21" }
}
