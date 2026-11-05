package org.qo.db.repository

import org.qo.datas.ReactiveDatabase
import org.qo.orm.booleanValue
import org.qo.services.loginService.KotshiPrivacySettings
import org.springframework.stereotype.Repository

@Repository
class KotshiPrivacyDbRepository(
	private val database: ReactiveDatabase,
) {
	private val schema = SchemaInitializer()

	suspend fun settings(username: String): KotshiPrivacySettings? {
		ensureSchema()
		return database.one(
			"SELECT kotshi_query_enabled, qbot_active_response_enabled FROM users WHERE username = ? LIMIT 1",
			listOf(username),
		) { row ->
			KotshiPrivacySettings(
				queryEnabled = booleanValue(row.get("kotshi_query_enabled")) ?: true,
				activeResponseEnabled = booleanValue(row.get("qbot_active_response_enabled")) ?: true,
			)
		}
	}

	suspend fun settingsByUid(uid: Long): KotshiPrivacySettings? {
		ensureSchema()
		return database.one(
			"SELECT kotshi_query_enabled, qbot_active_response_enabled FROM users WHERE uid = ? LIMIT 1",
			listOf(uid),
		) { row ->
			KotshiPrivacySettings(
				queryEnabled = booleanValue(row.get("kotshi_query_enabled")) ?: true,
				activeResponseEnabled = booleanValue(row.get("qbot_active_response_enabled")) ?: true,
			)
		}
	}

	suspend fun update(
		username: String,
		queryEnabled: Boolean? = null,
		activeResponseEnabled: Boolean? = null,
	): KotshiPrivacySettings? {
		ensureSchema()
		if (queryEnabled != null && activeResponseEnabled != null) {
			database.execute(
				"UPDATE users SET kotshi_query_enabled = ?, qbot_active_response_enabled = ? WHERE username = ?",
				listOf(queryEnabled, activeResponseEnabled, username),
			)
		} else if (queryEnabled != null) {
			database.execute(
				"UPDATE users SET kotshi_query_enabled = ? WHERE username = ?",
				listOf(queryEnabled, username),
			)
		} else if (activeResponseEnabled != null) {
			database.execute(
				"UPDATE users SET qbot_active_response_enabled = ? WHERE username = ?",
				listOf(activeResponseEnabled, username),
			)
		}
		return settings(username)
	}

	suspend fun updateByUid(uid: Long, activeResponseEnabled: Boolean): Boolean {
		ensureSchema()
		val affected = database.execute(
			"UPDATE users SET qbot_active_response_enabled = ? WHERE uid = ?",
			listOf(activeResponseEnabled, uid),
		)
		return affected > 0
	}

	private suspend fun ensureSchema() = schema.ensure {
		ensureColumn("kotshi_query_enabled", "BOOLEAN NOT NULL DEFAULT 1")
		ensureColumn("qbot_active_response_enabled", "BOOLEAN NOT NULL DEFAULT 1")
	}

	private suspend fun ensureColumn(columnName: String, definition: String) {
		val exists = database.one(
			"""
			SELECT 1
			FROM information_schema.columns
			WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
			  AND LOWER(table_name) = 'users'
			  AND LOWER(column_name) = LOWER(?)
			LIMIT 1
			""".trimIndent(),
			listOf(columnName),
		) { true } != null
		if (!exists) {
			database.execute(
				"ALTER TABLE users ADD COLUMN $columnName $definition",
			)
		}
	}
}
