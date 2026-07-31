package org.qo.services.llmServices

import jakarta.annotation.PostConstruct
import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Repository

interface LLMMemoryRepository {
	fun findByGroup(groupId: Long): List<LLMMemoryRecord>
	fun findByIdentity(groupId: Long, subject: String, memoryKey: String): LLMMemoryRecord?
	fun insert(record: LLMMemoryRecord): Boolean
	fun update(record: LLMMemoryRecord)
	fun delete(groupId: Long, ids: List<String>)
	fun isMigrationComplete(key: String): Boolean
	fun markMigrationComplete(key: String)
}

@Repository
class JdbcLLMMemoryRepository : LLMMemoryRepository {
	@PostConstruct
	fun init() {
		ConnectionPool.getConnection().use { connection ->
			connection.createStatement().use { statement ->
				statement.executeUpdate(
					"""
					CREATE TABLE IF NOT EXISTS llm_memories (
						id VARCHAR(36) PRIMARY KEY,
						group_id BIGINT NOT NULL,
						subject VARCHAR(160) NOT NULL,
						memory_key VARCHAR(80) NOT NULL,
						fact TEXT NOT NULL,
						category VARCHAR(40) NOT NULL,
						source_uid VARCHAR(128) NULL,
						source_name VARCHAR(160) NULL,
						created_at BIGINT NOT NULL,
						updated_at BIGINT NOT NULL,
						expires_at BIGINT NULL,
						UNIQUE KEY uk_llm_memory_identity (group_id, subject, memory_key),
						INDEX idx_llm_memory_group_updated (group_id, updated_at),
						INDEX idx_llm_memory_expires (expires_at)
					)
					""".trimIndent()
				)
				statement.executeUpdate(
					"""
					CREATE TABLE IF NOT EXISTS llm_memory_migrations (
						migration_key VARCHAR(128) PRIMARY KEY,
						completed_at BIGINT NOT NULL
					)
					""".trimIndent()
				)
			}
		}
	}

	override fun findByGroup(groupId: Long): List<LLMMemoryRecord> =
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"SELECT * FROM llm_memories WHERE group_id = ? ORDER BY updated_at DESC"
			).use { statement ->
				statement.setLong(1, groupId)
				statement.executeQuery().use { result ->
					buildList {
						while (result.next()) add(result.toMemoryRecord())
					}
				}
			}
		}

	override fun findByIdentity(groupId: Long, subject: String, memoryKey: String): LLMMemoryRecord? =
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"SELECT * FROM llm_memories WHERE group_id = ? AND subject = ? AND memory_key = ? LIMIT 1"
			).use { statement ->
				statement.setLong(1, groupId)
				statement.setString(2, subject)
				statement.setString(3, memoryKey)
				statement.executeQuery().use { result -> if (result.next()) result.toMemoryRecord() else null }
			}
		}

	override fun insert(record: LLMMemoryRecord): Boolean =
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"""
				INSERT IGNORE INTO llm_memories
				(id, group_id, subject, memory_key, fact, category, source_uid, source_name, created_at, updated_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent()
			).use { statement ->
				statement.bind(record)
				statement.executeUpdate() == 1
			}
		}

	override fun update(record: LLMMemoryRecord) {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"""
				UPDATE llm_memories SET fact = ?, category = ?, source_uid = ?, source_name = ?, updated_at = ?, expires_at = ?
				WHERE id = ? AND group_id = ?
				""".trimIndent()
			).use { statement ->
				statement.setString(1, record.fact)
				statement.setString(2, record.category)
				statement.setString(3, record.sourceUid)
				statement.setString(4, record.sourceName)
				statement.setLong(5, record.updatedAt)
				statement.setObject(6, record.expiresAt)
				statement.setString(7, record.id)
				statement.setLong(8, record.groupId)
				statement.executeUpdate()
			}
		}
	}

	override fun delete(groupId: Long, ids: List<String>) {
		if (ids.isEmpty()) return
		ConnectionPool.getConnection().use { connection ->
			val placeholders = ids.joinToString(",") { "?" }
			connection.prepareStatement("DELETE FROM llm_memories WHERE group_id = ? AND id IN ($placeholders)").use { statement ->
				statement.setLong(1, groupId)
				ids.forEachIndexed { index, id -> statement.setString(index + 2, id) }
				statement.executeUpdate()
			}
		}
	}

	override fun isMigrationComplete(key: String): Boolean =
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement("SELECT 1 FROM llm_memory_migrations WHERE migration_key = ?").use { statement ->
				statement.setString(1, key)
				statement.executeQuery().use { it.next() }
			}
		}

	override fun markMigrationComplete(key: String) {
		ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"INSERT IGNORE INTO llm_memory_migrations(migration_key, completed_at) VALUES (?, ?)"
			).use { statement ->
				statement.setString(1, key)
				statement.setLong(2, System.currentTimeMillis())
				statement.executeUpdate()
			}
		}
	}

	private fun java.sql.PreparedStatement.bind(record: LLMMemoryRecord) {
		setString(1, record.id)
		setLong(2, record.groupId)
		setString(3, record.subject)
		setString(4, record.memoryKey)
		setString(5, record.fact)
		setString(6, record.category)
		setString(7, record.sourceUid)
		setString(8, record.sourceName)
		setLong(9, record.createdAt)
		setLong(10, record.updatedAt)
		setObject(11, record.expiresAt)
	}

	private fun java.sql.ResultSet.toMemoryRecord(): LLMMemoryRecord = LLMMemoryRecord(
		id = getString("id"),
		groupId = getLong("group_id"),
		subject = getString("subject"),
		memoryKey = getString("memory_key"),
		fact = getString("fact"),
		category = getString("category"),
		sourceUid = getString("source_uid"),
		sourceName = getString("source_name"),
		createdAt = getLong("created_at"),
		updatedAt = getLong("updated_at"),
		expiresAt = getLong("expires_at").takeUnless { wasNull() },
	)
}
