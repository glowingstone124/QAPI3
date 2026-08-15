package org.qo.services.llmServices

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.qo.datas.ReactiveDatabase
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Repository

interface LLMMemoryRepository {
	suspend fun findByGroup(groupId: Long): List<LLMMemoryRecord>
	suspend fun findByIdentity(groupId: Long, subject: String, memoryKey: String): LLMMemoryRecord?
	suspend fun insert(record: LLMMemoryRecord): Boolean
	suspend fun update(record: LLMMemoryRecord)
	suspend fun delete(groupId: Long, ids: List<String>)
	suspend fun isMigrationComplete(key: String): Boolean
	suspend fun markMigrationComplete(key: String)
}

@Repository
class R2dbcLLMMemoryRepository(
	private val database: ReactiveDatabase,
) : LLMMemoryRepository {
	private val initializationScope = CoroutineScope(SupervisorJob())
	private val schemaReady = CompletableDeferred<Unit>()

	@EventListener(ApplicationReadyEvent::class)
	fun initializeSchema() {
		initializationScope.launch {
			try {
				database.execute(
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
				database.execute(
					"""
					CREATE TABLE IF NOT EXISTS llm_memory_migrations (
						migration_key VARCHAR(128) PRIMARY KEY,
						completed_at BIGINT NOT NULL
					)
					""".trimIndent()
				)
				schemaReady.complete(Unit)
			} catch (error: Exception) {
				schemaReady.completeExceptionally(error)
				println("LLM memory table init failed: ${error.message}")
			}
		}
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	override suspend fun findByGroup(groupId: Long): List<LLMMemoryRecord> {
		schemaReady.await()
		return database.all(
			"""
			SELECT id, group_id, subject, memory_key, fact, category, source_uid, source_name, created_at, updated_at, expires_at
			FROM llm_memories
			WHERE group_id = ?
			ORDER BY updated_at DESC
			""".trimIndent(),
			listOf(groupId),
			::toMemoryRecord,
		)
	}

	override suspend fun findByIdentity(groupId: Long, subject: String, memoryKey: String): LLMMemoryRecord? {
		schemaReady.await()
		return database.one(
			"""
			SELECT id, group_id, subject, memory_key, fact, category, source_uid, source_name, created_at, updated_at, expires_at
			FROM llm_memories
			WHERE group_id = ? AND subject = ? AND memory_key = ?
			LIMIT 1
			""".trimIndent(),
			listOf(groupId, subject, memoryKey),
			::toMemoryRecord,
		)
	}

	override suspend fun insert(record: LLMMemoryRecord): Boolean {
		schemaReady.await()
		return database.execute(
			"""
			INSERT IGNORE INTO llm_memories
			(id, group_id, subject, memory_key, fact, category, source_uid, source_name, created_at, updated_at, expires_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			record.bindings(),
		) == 1L
	}

	override suspend fun update(record: LLMMemoryRecord) {
		schemaReady.await()
		database.execute(
			"""
			UPDATE llm_memories
			SET fact = ?, category = ?, source_uid = ?, source_name = ?, updated_at = ?, expires_at = ?
			WHERE id = ? AND group_id = ?
			""".trimIndent(),
			listOf(
				record.fact,
				record.category,
				record.sourceUid,
				record.sourceName,
				record.updatedAt,
				record.expiresAt,
				record.id,
				record.groupId,
			),
		)
	}

	override suspend fun delete(groupId: Long, ids: List<String>) {
		if (ids.isEmpty()) return
		schemaReady.await()
		ids.distinct().chunked(200).forEach { batch ->
			if (batch.isEmpty()) return@forEach
			database.execute(
				"DELETE FROM llm_memories WHERE group_id = ? AND id IN (${batch.joinToString(", ") { "?" }})",
				listOf(groupId) + batch,
			)
		}
	}

	override suspend fun isMigrationComplete(key: String): Boolean {
		schemaReady.await()
		return database.one(
			"SELECT 1 FROM llm_memory_migrations WHERE migration_key = ?",
			listOf(key),
		) { true } != null
	}

	override suspend fun markMigrationComplete(key: String) {
		schemaReady.await()
		database.execute(
			"INSERT IGNORE INTO llm_memory_migrations(migration_key, completed_at) VALUES (?, ?)",
			listOf(key, System.currentTimeMillis()),
		)
	}

	private fun LLMMemoryRecord.bindings(): List<Any?> = listOf(
		id,
		groupId,
		subject,
		memoryKey,
		fact,
		category,
		sourceUid,
		sourceName,
		createdAt,
		updatedAt,
		expiresAt,
	)

	private fun toMemoryRecord(row: io.r2dbc.spi.Row): LLMMemoryRecord = LLMMemoryRecord(
		id = row.get("id", String::class.java)!!,
		groupId = row.get("group_id", java.lang.Long::class.java)!!.toLong(),
		subject = row.get("subject", String::class.java)!!,
		memoryKey = row.get("memory_key", String::class.java)!!,
		fact = row.get("fact", String::class.java)!!,
		category = row.get("category", String::class.java)!!,
		sourceUid = row.get("source_uid", String::class.java),
		sourceName = row.get("source_name", String::class.java),
		createdAt = row.get("created_at", java.lang.Long::class.java)!!.toLong(),
		updatedAt = row.get("updated_at", java.lang.Long::class.java)!!.toLong(),
		expiresAt = row.get("expires_at", java.lang.Long::class.java)?.toLong(),
	)
}
