package org.qo.db.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Full turn archive plus the compact context needed to resume a conversation after a restart. */
@Repository
class LlmConversationHistoryDbRepository(private val database: ReactiveDatabase) {
	private val schemaMutex = Mutex()
	@Volatile private var schemaReady = false

	private suspend fun ensureSchema() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute("""
				CREATE TABLE IF NOT EXISTS llm_conversation_turns (
					id BIGINT AUTO_INCREMENT PRIMARY KEY,
					conversation_hash CHAR(64) NOT NULL,
					conversation_key TEXT NOT NULL,
					user_content LONGTEXT NOT NULL,
					assistant_content MEDIUMTEXT NOT NULL,
					created_at BIGINT NOT NULL,
					INDEX idx_llm_conversation_turns (conversation_hash, id)
				)
			""".trimIndent())
			database.execute("""
				CREATE TABLE IF NOT EXISTS llm_conversation_state (
					conversation_hash CHAR(64) PRIMARY KEY,
					conversation_key TEXT NOT NULL,
					state_json LONGTEXT NOT NULL,
					updated_at BIGINT NOT NULL
				)
			""".trimIndent())
			schemaReady = true
		}
	}

	suspend fun loadState(conversationKey: String): String? {
		ensureSchema()
		return database.one(
			"SELECT state_json FROM llm_conversation_state WHERE conversation_hash = ?",
			listOf(hash(conversationKey)),
		) { row -> row.get("state_json", String::class.java) }
	}

	suspend fun appendTurnAndState(
		conversationKey: String,
		userContent: String,
		assistantContent: String,
		createdAt: Long,
		stateJson: String,
	) {
		ensureSchema()
		val keyHash = hash(conversationKey)
		database.inTransaction {
			database.execute("""
				INSERT INTO llm_conversation_turns
				(conversation_hash, conversation_key, user_content, assistant_content, created_at)
				VALUES (?, ?, ?, ?, ?)
			""".trimIndent(), listOf(keyHash, conversationKey, userContent, assistantContent, createdAt))
			upsertState(keyHash, conversationKey, stateJson, createdAt)
		}
	}

	suspend fun saveState(conversationKey: String, stateJson: String, updatedAt: Long) {
		ensureSchema()
		upsertState(hash(conversationKey), conversationKey, stateJson, updatedAt)
	}

	/** Remove an explicitly deleted conversation and return its image-bearing user turns for cleanup. */
	suspend fun deleteConversation(conversationKey: String): List<String> {
		ensureSchema()
		val keyHash = hash(conversationKey)
		return database.inTransaction {
			val userContents = database.all(
				"SELECT user_content FROM llm_conversation_turns WHERE conversation_hash = ? ORDER BY id",
				listOf(keyHash),
			) { row -> row.get("user_content", String::class.java).orEmpty() }
			database.execute("DELETE FROM llm_conversation_turns WHERE conversation_hash = ?", listOf(keyHash))
			database.execute("DELETE FROM llm_conversation_state WHERE conversation_hash = ?", listOf(keyHash))
			userContents
		}
	}

	private suspend fun upsertState(hash: String, key: String, json: String, updatedAt: Long) {
		database.execute("""
			INSERT INTO llm_conversation_state (conversation_hash, conversation_key, state_json, updated_at)
			VALUES (?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE state_json = ?, updated_at = ?
		""".trimIndent(), listOf(hash, key, json, updatedAt, json, updatedAt))
	}

	private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { "%02x".format(it) }
}
