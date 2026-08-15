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

interface LLMChatHistoryRepository {
	suspend fun insert(records: List<LLMChatHistoryRecord>): Int
	suspend fun search(groupId: Long, query: String, uid: Long?, fromTime: Long?, toTime: Long?, limit: Int): List<LLMChatHistoryRecord>
}

@Repository
class R2dbcLLMChatHistoryRepository(
	private val database: ReactiveDatabase,
) : LLMChatHistoryRepository {
	private val initializationScope = CoroutineScope(SupervisorJob())
	private val schemaReady = CompletableDeferred<Unit>()

	@EventListener(ApplicationReadyEvent::class)
	fun initializeSchema() {
		initializationScope.launch {
			try {
				database.execute(
					"""
					CREATE TABLE IF NOT EXISTS llm_chat_history (
						id BIGINT AUTO_INCREMENT PRIMARY KEY,
						source_id VARCHAR(80) NOT NULL,
						group_id BIGINT NOT NULL,
						uid BIGINT NOT NULL,
						name VARCHAR(160) NOT NULL,
						content TEXT NOT NULL,
						message_time BIGINT NOT NULL,
						created_at BIGINT NOT NULL,
						UNIQUE KEY uk_llm_chat_source (group_id, source_id),
						INDEX idx_llm_chat_group_time (group_id, message_time),
						INDEX idx_llm_chat_group_uid_time (group_id, uid, message_time)
					)
					""".trimIndent()
				)
				schemaReady.complete(Unit)
			} catch (error: Exception) {
				schemaReady.completeExceptionally(error)
				println("LLM chat history table init failed: ${error.message}")
			}
		}
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	override suspend fun insert(records: List<LLMChatHistoryRecord>): Int {
		if (records.isEmpty()) return 0
		schemaReady.await()
		return records.chunked(200).sumOf { batch ->
			val placeholders = batch.joinToString(", ") { "(?, ?, ?, ?, ?, ?, ?)" }
			database.execute(
				"""
				INSERT IGNORE INTO llm_chat_history
				(source_id, group_id, uid, name, content, message_time, created_at)
				VALUES $placeholders
				""".trimIndent(),
				batch.flatMap { record ->
					listOf(
						record.sourceId,
						record.groupId,
						record.uid,
						record.name,
						record.content,
						record.time,
						record.createdAt,
					)
				}
			).toInt()
		}
	}

	override suspend fun search(
		groupId: Long,
		query: String,
		uid: Long?,
		fromTime: Long?,
		toTime: Long?,
		limit: Int,
	): List<LLMChatHistoryRecord> {
		schemaReady.await()
		val clauses = mutableListOf("group_id = ?")
		val bindings = mutableListOf<Any?>(groupId)
		if (query.isNotBlank()) {
			clauses += "(content LIKE ? OR name LIKE ?)"
			val pattern = "%${query.take(200)}%"
			bindings += pattern
			bindings += pattern
		}
		if (uid != null) {
			clauses += "uid = ?"
			bindings += uid
		}
		if (fromTime != null) {
			clauses += "message_time >= ?"
			bindings += fromTime
		}
		if (toTime != null) {
			clauses += "message_time <= ?"
			bindings += toTime
		}
		bindings += limit.coerceIn(1, 50)
		return database.all(
			"""
			SELECT source_id, group_id, uid, name, content, message_time, created_at
			FROM llm_chat_history
			WHERE ${clauses.joinToString(" AND ")}
			ORDER BY message_time DESC
			LIMIT ?
			""".trimIndent(),
			bindings,
		) { row ->
			LLMChatHistoryRecord(
				sourceId = row.get("source_id", String::class.java)!!,
				groupId = row.get("group_id", java.lang.Long::class.java)!!.toLong(),
				uid = row.get("uid", java.lang.Long::class.java)!!.toLong(),
				name = row.get("name", String::class.java)!!,
				content = row.get("content", String::class.java)!!,
				time = row.get("message_time", java.lang.Long::class.java)!!.toLong(),
				createdAt = row.get("created_at", java.lang.Long::class.java)!!.toLong(),
			)
		}
	}
}
