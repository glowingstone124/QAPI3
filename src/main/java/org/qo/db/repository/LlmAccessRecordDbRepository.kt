package org.qo.db.repository

import io.r2dbc.spi.R2dbcException
import io.r2dbc.spi.Row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.llmServices.KotshiUsageRecord
import org.qo.services.llmServices.KotshiUsageSummary
import org.springframework.stereotype.Repository

@Repository
class LlmAccessRecordDbRepository(
	private val database: ReactiveDatabase,
) {
	private val mutex = Mutex()
	@Volatile
	private var ready = false

	suspend fun ensureSchema() {
		if (ready) return
		mutex.withLock {
			if (ready) return
			database.execute(
				"""
				CREATE TABLE IF NOT EXISTS llm_access_records (
					id BIGINT AUTO_INCREMENT PRIMARY KEY,
					uid BIGINT NOT NULL,
					username VARCHAR(128) NOT NULL,
					source VARCHAR(32) NOT NULL DEFAULT 'unknown',
					source_identity VARCHAR(128) NULL,
					group_name VARCHAR(128) NULL,
					request_id VARCHAR(80) NOT NULL,
					model VARCHAR(128) NOT NULL,
					stream BOOLEAN NOT NULL,
					status VARCHAR(32) NOT NULL,
					prompt_tokens INT NULL,
					completion_tokens INT NULL,
					total_tokens INT NULL,
					cached_tokens INT NULL,
					uncached_tokens INT NULL,
					error_message VARCHAR(512) NULL,
					created_at BIGINT NOT NULL,
					completed_at BIGINT NULL,
					INDEX idx_llm_access_uid_created (uid, created_at)
				)
				""".trimIndent(),
			)
			ensureColumn("source", "VARCHAR(32) NOT NULL DEFAULT 'unknown'")
			ensureColumn("source_identity", "VARCHAR(128) NULL")
			ensureColumn("group_name", "VARCHAR(128) NULL")
			ensureColumn("cached_tokens", "INT NULL")
			ensureColumn("uncached_tokens", "INT NULL")
			ready = true
		}
	}

	private suspend fun columnExists(name: String): Boolean = database.one(
		"""
		SELECT 1 FROM information_schema.columns
		WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
		  AND LOWER(table_name) = 'llm_access_records'
		  AND LOWER(column_name) = LOWER(?)
		LIMIT 1
		""".trimIndent(),
		listOf(name),
	) { true } != null

	private suspend fun ensureColumn(name: String, definition: String) {
		if (columnExists(name)) return
		try {
			database.execute("ALTER TABLE llm_access_records ADD COLUMN $name $definition")
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			if (isDuplicateColumn(error) && columnExists(name)) return
			val detail = accessRecordSchemaErrorDetail(error)
			throw IllegalStateException("LLM access record column migration failed for $name: $detail", error)
		}
	}

	private fun accessRecordSchemaErrorDetail(error: Throwable): String {
		val cause = generateSequence(error) { it.cause }.last()
		return if (cause is R2dbcException) "SQLSTATE=${cause.sqlState} code=${cause.errorCode}: ${cause.message}"
		else cause.message ?: cause.javaClass.simpleName
	}

	private fun isDuplicateColumn(error: Throwable): Boolean =
		generateSequence(error) { it.cause }.filterIsInstance<R2dbcException>()
			.any { it.errorCode == 1060 || it.sqlState == "42S21" }

	suspend fun insertStartRecord(
		uid: Long,
		username: String,
		source: String,
		sourceIdentity: String?,
		groupName: String?,
		requestId: String,
		model: String,
		stream: Boolean,
		createdAt: Long,
	): Long {
		ensureSchema()
		return database.inTransaction {
			database.execute(
				"""
				INSERT INTO llm_access_records(
					uid, username, source, source_identity, group_name, request_id, model, stream, status, created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				listOf(
					uid,
					username.take(128),
					source,
					sourceIdentity?.take(128),
					groupName?.take(128),
					requestId,
					model.take(128),
					stream,
					"started",
					createdAt,
				),
			)
			database.one(
				"SELECT id FROM llm_access_records WHERE request_id = ? ORDER BY id DESC LIMIT 1",
				listOf(requestId),
			) { row ->
				row.get("id", java.lang.Long::class.java)!!.toLong()
			} ?: -1L
		}
	}

	suspend fun updateRecord(
		id: Long,
		status: String,
		promptTokens: Int?,
		completionTokens: Int?,
		totalTokens: Int?,
		cachedTokens: Int?,
		uncachedTokens: Int?,
		errorMessage: String?,
		completedAt: Long,
	) {
		if (id <= 0) return
		ensureSchema()
		database.execute(
			"""
			UPDATE llm_access_records
			SET status = ?, prompt_tokens = ?, completion_tokens = ?, total_tokens = ?, cached_tokens = ?, uncached_tokens = ?, error_message = ?, completed_at = ?
			WHERE id = ?
			""".trimIndent(),
			listOf(
				status,
				promptTokens,
				completionTokens,
				totalTokens,
				cachedTokens,
				uncachedTokens,
				errorMessage?.take(512),
				completedAt,
				id,
			),
		)
	}

	suspend fun loadUsageSummary(uid: Long, dayStart: Long): KotshiUsageSummary {
		ensureSchema()
		return database.one(
			"""
			SELECT COUNT(*) AS requests,
			       SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS completed,
			       SUM(CASE WHEN status IN ('failed', 'rejected') THEN 1 ELSE 0 END) AS failed,
			       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
			       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
			       COALESCE(SUM(total_tokens), 0) AS total_tokens
			FROM llm_access_records
			WHERE uid = ? AND created_at >= ?
			""".trimIndent(),
			listOf(uid, dayStart),
		) { row ->
			KotshiUsageSummary(
				requests = number(row.get("requests")),
				completed = number(row.get("completed")),
				failed = number(row.get("failed")),
				promptTokens = number(row.get("prompt_tokens")),
				completionTokens = number(row.get("completion_tokens")),
				totalTokens = number(row.get("total_tokens")),
			)
		} ?: KotshiUsageSummary()
	}

	suspend fun loadRecentUsage(uid: Long, limit: Int = 20): List<KotshiUsageRecord> {
		ensureSchema()
		return database.all(
			"""
			SELECT source, status, total_tokens, created_at, completed_at
			FROM llm_access_records
			WHERE uid = ?
			ORDER BY created_at DESC
			LIMIT ?
			""".trimIndent(),
			listOf(uid, limit),
		) { row ->
			KotshiUsageRecord(
				source = row.get("source", String::class.java) ?: "unknown",
				status = row.get("status", String::class.java) ?: "unknown",
				totalTokens = number(row.get("total_tokens")),
				createdAt = number(row.get("created_at")),
				completedAt = row.get("completed_at")?.let(::number),
			)
		}
	}

	private fun number(value: Any?): Long = when (value) {
		null -> 0L
		is Number -> value.toLong()
		is String -> value.toLongOrNull() ?: 0L
		else -> 0L
	}
}
