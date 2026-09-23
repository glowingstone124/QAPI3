package org.qo.db.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.llmServices.KotshiConversation
import org.qo.services.llmServices.KotshiMessage
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class KotshiConversationDbRepository(
	private val database: ReactiveDatabase,
) {
	private val schemaMutex = Mutex()
	@Volatile
	private var schemaReady = false

	suspend fun ensureSchema() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute(
				"""
				CREATE TABLE IF NOT EXISTS kotshi_conversations (
					id VARCHAR(64) PRIMARY KEY,
					uid BIGINT NOT NULL,
					title VARCHAR(255) NOT NULL,
					model VARCHAR(64) NOT NULL DEFAULT 'fast',
					created_at BIGINT NOT NULL,
					updated_at BIGINT NOT NULL,
					INDEX idx_kotshi_conv_uid_updated (uid, updated_at DESC)
				)
				""".trimIndent()
			)
			database.execute(
				"""
				CREATE TABLE IF NOT EXISTS kotshi_messages (
					id BIGINT AUTO_INCREMENT PRIMARY KEY,
					conversation_id VARCHAR(64) NOT NULL,
					uid BIGINT NOT NULL,
					role VARCHAR(32) NOT NULL,
					content MEDIUMTEXT NOT NULL,
					created_at BIGINT NOT NULL,
					INDEX idx_kotshi_msg_conv_created (conversation_id, created_at ASC)
				)
				""".trimIndent()
			)
			schemaReady = true
		}
	}

	suspend fun listConversations(uid: Long): List<KotshiConversation> {
		ensureSchema()
		return database.all(
			"""
			SELECT id, uid, title, model, created_at, updated_at
			FROM kotshi_conversations
			WHERE uid = ?
			ORDER BY updated_at DESC
			""".trimIndent(),
			listOf(uid),
		) { row ->
			KotshiConversation(
				id = row.get("id", String::class.java) ?: "",
				uid = (row.get("uid", Number::class.java)?.toLong()) ?: uid,
				title = row.get("title", String::class.java) ?: "新对话",
				model = row.get("model", String::class.java) ?: "fast",
				createdAt = (row.get("created_at", Number::class.java)?.toLong()) ?: 0L,
				updatedAt = (row.get("updated_at", Number::class.java)?.toLong()) ?: 0L,
			)
		}
	}

	suspend fun getConversation(uid: Long, conversationId: String): KotshiConversation? {
		ensureSchema()
		return database.one(
			"""
			SELECT id, uid, title, model, created_at, updated_at
			FROM kotshi_conversations
			WHERE uid = ? AND id = ?
			LIMIT 1
			""".trimIndent(),
			listOf(uid, conversationId),
		) { row ->
			KotshiConversation(
				id = row.get("id", String::class.java) ?: "",
				uid = (row.get("uid", Number::class.java)?.toLong()) ?: uid,
				title = row.get("title", String::class.java) ?: "新对话",
				model = row.get("model", String::class.java) ?: "fast",
				createdAt = (row.get("created_at", Number::class.java)?.toLong()) ?: 0L,
				updatedAt = (row.get("updated_at", Number::class.java)?.toLong()) ?: 0L,
			)
		}
	}

	suspend fun createConversation(
		uid: Long,
		title: String? = null,
		model: String = "fast",
		customId: String? = null,
	): KotshiConversation {
		ensureSchema()
		val id = customId?.takeIf { it.isNotBlank() } ?: "conv-${UUID.randomUUID().toString().replace("-", "").take(16)}"
		val now = System.currentTimeMillis()
		val convTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: "新对话"
		database.execute(
			"""
			INSERT INTO kotshi_conversations (id, uid, title, model, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE updated_at = ?
			""".trimIndent(),
			listOf(id, uid, convTitle, model, now, now, now),
		)
		return KotshiConversation(id = id, uid = uid, title = convTitle, model = model, createdAt = now, updatedAt = now)
	}

	suspend fun updateConversation(uid: Long, conversationId: String, title: String?, model: String?): Boolean {
		ensureSchema()
		val updates = mutableListOf<String>()
		val params = mutableListOf<Any>()
		if (title != null) {
			updates.add("title = ?")
			params.add(title.trim().take(255))
		}
		if (model != null) {
			updates.add("model = ?")
			params.add(model.trim().take(64))
		}
		if (updates.isEmpty()) return true
		updates.add("updated_at = ?")
		val now = System.currentTimeMillis()
		params.add(now)
		params.add(uid)
		params.add(conversationId)
		val sql = "UPDATE kotshi_conversations SET ${updates.joinToString(", ")} WHERE uid = ? AND id = ?"
		return database.execute(sql, params) > 0
	}

	suspend fun touchConversation(uid: Long, conversationId: String, updatedAt: Long = System.currentTimeMillis()) {
		ensureSchema()
		database.execute(
			"UPDATE kotshi_conversations SET updated_at = ? WHERE uid = ? AND id = ?",
			listOf(updatedAt, uid, conversationId),
		)
	}

	suspend fun deleteConversation(uid: Long, conversationId: String): Boolean {
		ensureSchema()
		database.execute("DELETE FROM kotshi_messages WHERE conversation_id = ? AND uid = ?", listOf(conversationId, uid))
		return database.execute("DELETE FROM kotshi_conversations WHERE id = ? AND uid = ?", listOf(conversationId, uid)) > 0
	}

	suspend fun getMessages(uid: Long, conversationId: String): List<KotshiMessage> {
		ensureSchema()
		return database.all(
			"""
			SELECT id, conversation_id, uid, role, content, created_at
			FROM kotshi_messages
			WHERE uid = ? AND conversation_id = ?
			ORDER BY created_at ASC, id ASC
			""".trimIndent(),
			listOf(uid, conversationId),
		) { row ->
			KotshiMessage(
				id = (row.get("id", Number::class.java)?.toLong()) ?: 0L,
				conversationId = row.get("conversation_id", String::class.java) ?: conversationId,
				uid = (row.get("uid", Number::class.java)?.toLong()) ?: uid,
				role = row.get("role", String::class.java) ?: "user",
				content = row.get("content", String::class.java) ?: "",
				createdAt = (row.get("created_at", Number::class.java)?.toLong()) ?: 0L,
			)
		}
	}

	suspend fun appendTurnMessages(
		conversationId: String,
		uid: Long,
		userContent: String,
		assistantContent: String,
		now: Long = System.currentTimeMillis(),
	) {
		ensureSchema()
		database.execute(
			"""
			INSERT INTO kotshi_messages (conversation_id, uid, role, content, created_at)
			VALUES (?, ?, 'user', ?, ?), (?, ?, 'assistant', ?, ?)
			""".trimIndent(),
			listOf(
				conversationId, uid, userContent, now,
				conversationId, uid, assistantContent, now + 1,
			),
		)
	}

	suspend fun appendMessage(uid: Long, conversationId: String, role: String, content: String): KotshiMessage? {
		ensureSchema()
		val now = System.currentTimeMillis()
		database.execute(
			"""
			INSERT INTO kotshi_messages (conversation_id, uid, role, content, created_at)
			VALUES (?, ?, ?, ?, ?)
			""".trimIndent(),
			listOf(conversationId, uid, role.take(32), content, now),
		)
		database.execute(
			"UPDATE kotshi_conversations SET updated_at = ? WHERE id = ? AND uid = ?",
			listOf(now, conversationId, uid),
		)
		return KotshiMessage(
			id = 0L,
			conversationId = conversationId,
			uid = uid,
			role = role,
			content = content,
			createdAt = now,
		)
	}
}
