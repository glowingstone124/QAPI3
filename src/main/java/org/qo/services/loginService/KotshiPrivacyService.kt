package org.qo.services.loginService

import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.orm.booleanValue
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class KotshiPrivacySettings(
	val queryEnabled: Boolean,
)

/** Stores the account-level permission used by Kotshi's player lookup path. */
@Service
class KotshiPrivacyService(
	private val database: ReactiveDatabase,
) {
	private val schemaMutex = Mutex()
	@Volatile
	private var schemaReady = false

	suspend fun settings(username: String): KotshiPrivacySettings? {
		ensureSchema()
		return database.one(
			"SELECT kotshi_query_enabled FROM users WHERE username = ? LIMIT 1",
			listOf(username),
		) { row ->
			KotshiPrivacySettings(
				queryEnabled = booleanValue(row.get("kotshi_query_enabled")) ?: true,
			)
		}
	}

	suspend fun isQueryEnabled(username: String): Boolean = settings(username)?.queryEnabled ?: false

	fun isQueryEnabledReactive(username: String): Mono<Boolean> = mono {
		isQueryEnabled(username)
	}

	suspend fun update(username: String, enabled: Boolean): KotshiPrivacySettings? {
		ensureSchema()
		database.execute(
			"UPDATE users SET kotshi_query_enabled = ? WHERE username = ?",
			listOf(enabled, username),
		)
		return settings(username)
	}

	private suspend fun ensureSchema() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			val exists = database.one(
				"""
				SELECT 1
				FROM information_schema.columns
				WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
				  AND LOWER(table_name) = 'users'
				  AND LOWER(column_name) = 'kotshi_query_enabled'
				LIMIT 1
				""".trimIndent(),
			) { true } != null
			if (!exists) {
				database.execute(
					"ALTER TABLE users ADD COLUMN kotshi_query_enabled BOOLEAN NOT NULL DEFAULT 1",
				)
			}
			schemaReady = true
		}
	}
}
