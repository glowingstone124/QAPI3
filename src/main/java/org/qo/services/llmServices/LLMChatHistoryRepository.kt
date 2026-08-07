package org.qo.services.llmServices

import jakarta.annotation.PostConstruct
import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Repository

interface LLMChatHistoryRepository {
	fun insert(records: List<LLMChatHistoryRecord>): Int
	fun search(groupId: Long, query: String, uid: Long?, fromTime: Long?, toTime: Long?, limit: Int): List<LLMChatHistoryRecord>
}

@Repository
class JdbcLLMChatHistoryRepository : LLMChatHistoryRepository {
	@PostConstruct
	fun init() {
		ConnectionPool.getConnection().use { connection ->
			connection.createStatement().use { statement ->
				statement.executeUpdate(
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
			}
		}
	}

	override fun insert(records: List<LLMChatHistoryRecord>): Int {
		if (records.isEmpty()) return 0
		return ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(
				"""
				INSERT IGNORE INTO llm_chat_history
				(source_id, group_id, uid, name, content, message_time, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""".trimIndent()
			).use { statement ->
				records.forEach { record ->
					statement.setString(1, record.sourceId)
					statement.setLong(2, record.groupId)
					statement.setLong(3, record.uid)
					statement.setString(4, record.name)
					statement.setString(5, record.content)
					statement.setLong(6, record.time)
					statement.setLong(7, record.createdAt)
					statement.addBatch()
				}
				statement.executeBatch().count { it > 0 }
			}
		}
	}

	override fun search(
		groupId: Long,
		query: String,
		uid: Long?,
		fromTime: Long?,
		toTime: Long?,
		limit: Int,
	): List<LLMChatHistoryRecord> {
		val clauses = mutableListOf("group_id = ?")
		if (query.isNotBlank()) clauses += "(content LIKE ? OR name LIKE ?)"
		if (uid != null) clauses += "uid = ?"
		if (fromTime != null) clauses += "message_time >= ?"
		if (toTime != null) clauses += "message_time <= ?"
		val sql = "SELECT * FROM llm_chat_history WHERE ${clauses.joinToString(" AND ")} ORDER BY message_time DESC LIMIT ?"
		return ConnectionPool.getConnection().use { connection ->
			connection.prepareStatement(sql).use { statement ->
				var index = 1
				statement.setLong(index++, groupId)
				if (query.isNotBlank()) {
					val pattern = "%${query.take(200)}%"
					statement.setString(index++, pattern)
					statement.setString(index++, pattern)
				}
				if (uid != null) statement.setLong(index++, uid)
				if (fromTime != null) statement.setLong(index++, fromTime)
				if (toTime != null) statement.setLong(index++, toTime)
				statement.setInt(index, limit.coerceIn(1, 50))
				statement.executeQuery().use { result ->
					buildList {
						while (result.next()) {
							add(LLMChatHistoryRecord(
								sourceId = result.getString("source_id"),
								groupId = result.getLong("group_id"),
								uid = result.getLong("uid"),
								name = result.getString("name"),
								content = result.getString("content"),
								time = result.getLong("message_time"),
								createdAt = result.getLong("created_at"),
							))
						}
					}
				}
			}
		}
	}
}
