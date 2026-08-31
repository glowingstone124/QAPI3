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
import org.springframework.stereotype.Service
import java.util.UUID

data class KotshiConversation(
	val id: String,
	val uid: Long,
	val title: String,
	val model: String,
	val createdAt: Long,
	val updatedAt: Long,
)

data class KotshiMessage(
	val id: Long,
	val conversationId: String,
	val uid: Long,
	val role: String,
	val content: String,
	val createdAt: Long,
)

@Service
class KotshiConversationService(
	private val database: ReactiveDatabase,
) {
	private val initializationScope = CoroutineScope(SupervisorJob())
	private val schemaReady = CompletableDeferred<Unit>()

	@EventListener(ApplicationReadyEvent::class)
	fun initializeSchema() {
		initializationScope.launch {
			try {
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
				schemaReady.complete(Unit)
			} catch (error: Exception) {
				schemaReady.completeExceptionally(error)
				println("[Kotshi] conversation tables init failed: ${error.message}")
			}
		}
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	suspend fun listConversations(uid: Long): List<KotshiConversation> {
		schemaReady.await()
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
		schemaReady.await()
		return database.one(
			"""
			SELECT id, uid, title, model, created_at, updated_at
			FROM kotshi_conversations
			WHERE uid = ? AND id = ?
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
		schemaReady.await()
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

		return KotshiConversation(
			id = id,
			uid = uid,
			title = convTitle,
			model = model,
			createdAt = now,
			updatedAt = now,
		)
	}

	suspend fun updateConversation(
		uid: Long,
		conversationId: String,
		title: String? = null,
		model: String? = null,
	): Boolean {
		schemaReady.await()
		val updates = mutableListOf<String>()
		val bindings = mutableListOf<Any?>()

		if (title != null) {
			updates += "title = ?"
			bindings += title.trim().take(255)
		}
		if (model != null) {
			updates += "model = ?"
			bindings += model.trim().take(64)
		}
		if (updates.isEmpty()) return true

		val now = System.currentTimeMillis()
		updates += "updated_at = ?"
		bindings += now
		bindings += uid
		bindings += conversationId

		val rows = database.execute(
			"""
			UPDATE kotshi_conversations
			SET ${updates.joinToString(", ")}
			WHERE uid = ? AND id = ?
			""".trimIndent(),
			bindings,
		)
		return rows > 0
	}

	suspend fun deleteConversation(uid: Long, conversationId: String): Boolean {
		schemaReady.await()
		database.execute(
			"DELETE FROM kotshi_messages WHERE uid = ? AND conversation_id = ?",
			listOf(uid, conversationId),
		)
		val rows = database.execute(
			"DELETE FROM kotshi_conversations WHERE uid = ? AND id = ?",
			listOf(uid, conversationId),
		)
		return rows > 0
	}

	suspend fun getMessages(uid: Long, conversationId: String): List<KotshiMessage> {
		schemaReady.await()
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

	suspend fun appendTurn(
		uid: Long,
		conversationId: String,
		userContent: String,
		assistantContent: String,
		model: String = "fast",
	) {
		schemaReady.await()
		val now = System.currentTimeMillis()
		val existing = getConversation(uid, conversationId)

		val autoTitle = if (existing == null || existing.title == "新对话" || existing.title.isBlank()) {
			userContent.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(28) ?: "新对话"
		} else null

		if (existing == null) {
			createConversation(
				uid = uid,
				title = autoTitle,
				model = model,
				customId = conversationId,
			)
		} else if (autoTitle != null && autoTitle != existing.title) {
			updateConversation(uid, conversationId, title = autoTitle)
		} else {
			database.execute(
				"UPDATE kotshi_conversations SET updated_at = ? WHERE uid = ? AND id = ?",
				listOf(now, uid, conversationId),
			)
		}

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
}
