package org.qo.services.llmServices

import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Component

/** Shared by completion logging and account usage lookup; safe to rerun on existing tables. */
@Component
class LLMAccessRecordSchema(private val database: ReactiveDatabase) {
    private val mutex = Mutex()
    @Volatile private var ready = false

    suspend fun ensure() {
        if (ready) return
        mutex.withLock {
            if (ready) return
            database.execute("""
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
            """.trimIndent())
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
            // Another application instance may have added it between SELECT and ALTER.
            if (isDuplicateAccessRecordColumn(error) && columnExists(name)) return
            throw IllegalStateException("LLM access record column migration failed for $name: ${accessRecordSchemaErrorDetail(error)}", error)
        }
    }
}

internal fun isDuplicateAccessRecordColumn(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.filterIsInstance<R2dbcException>()
        .any { it.errorCode == 1060 || it.sqlState == "42S21" }

internal fun accessRecordSchemaErrorDetail(error: Throwable): String {
    val cause = generateSequence(error) { it.cause }.last()
    return if (cause is R2dbcException) "SQLSTATE=${cause.sqlState} code=${cause.errorCode}: ${cause.message}"
    else cause.message ?: cause.javaClass.simpleName
}
