package org.qo.services.llmServices

import io.r2dbc.spi.Row
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

data class LLMGroupTokenStats(
	val groupName: String,
	val cachedTokens: Long,
	val uncachedTokens: Long,
	val completionTokens: Long,
	val totalTokens: Long,
	val requestCount: Long,
	val createdAt: Long,
	val updatedAt: Long,
)

data class LLMUserTokenStats(
	val qqUid: Long,
	val cachedTokens: Long,
	val uncachedTokens: Long,
	val completionTokens: Long,
	val totalTokens: Long,
	val requestCount: Long,
	val createdAt: Long,
	val updatedAt: Long,
)

interface LLMTokenStatsRepository {
	suspend fun recordGroupTokens(
		groupName: String,
		cachedTokens: Long,
		uncachedTokens: Long,
		completionTokens: Long,
		totalTokens: Long,
	): Boolean

	suspend fun recordUserTokens(
		qqUid: Long,
		cachedTokens: Long,
		uncachedTokens: Long,
		completionTokens: Long,
		totalTokens: Long,
	): Boolean

	suspend fun getGroupStats(groupName: String): LLMGroupTokenStats?
	suspend fun listGroupStats(limit: Int = 100): List<LLMGroupTokenStats>

	suspend fun getUserStats(qqUid: Long): LLMUserTokenStats?
	suspend fun listUserStats(limit: Int = 100): List<LLMUserTokenStats>
}

@Repository
class R2dbcLLMTokenStatsRepository(
	private val database: ReactiveDatabase,
) : LLMTokenStatsRepository {
	private val initializationScope = CoroutineScope(SupervisorJob())
	private val schemaReady = CompletableDeferred<Unit>()

	@EventListener(ApplicationReadyEvent::class)
	fun initializeSchema() {
		ensureSchemaInitialization()
	}

	fun ensureSchemaInitialization() {
		initializationScope.launch {
			try {
				TokenStatsOwner.entries.forEach { owner ->
					database.execute(
						"""
						CREATE TABLE IF NOT EXISTS ${owner.table} (
							${owner.keyColumn} ${owner.keyType} NOT NULL PRIMARY KEY,
							cached_tokens BIGINT NOT NULL DEFAULT 0,
							uncached_tokens BIGINT NOT NULL DEFAULT 0,
							completion_tokens BIGINT NOT NULL DEFAULT 0,
							total_tokens BIGINT NOT NULL DEFAULT 0,
							request_count BIGINT NOT NULL DEFAULT 0,
							created_at BIGINT NOT NULL,
							updated_at BIGINT NOT NULL
						)
						""".trimIndent(),
					)
				}
				schemaReady.complete(Unit)
			} catch (error: Exception) {
				schemaReady.completeExceptionally(error)
				println("LLM token stats table init failed: ${error.message}")
			}
		}
	}

	suspend fun awaitSchema() {
		if (!schemaReady.isCompleted) {
			ensureSchemaInitialization()
		}
		schemaReady.await()
	}

	@PreDestroy
	fun shutdown() {
		initializationScope.cancel()
	}

	override suspend fun recordGroupTokens(
		groupName: String,
		cachedTokens: Long,
		uncachedTokens: Long,
		completionTokens: Long,
		totalTokens: Long,
	): Boolean = if (groupName.isBlank()) false else recordTokens(
		TokenStatsOwner.GROUP, groupName, cachedTokens, uncachedTokens, completionTokens, totalTokens,
	)

	override suspend fun recordUserTokens(
		qqUid: Long,
		cachedTokens: Long,
		uncachedTokens: Long,
		completionTokens: Long,
		totalTokens: Long,
	): Boolean = if (qqUid <= 0) false else recordTokens(
		TokenStatsOwner.USER, qqUid, cachedTokens, uncachedTokens, completionTokens, totalTokens,
	)

	private suspend fun recordTokens(
		owner: TokenStatsOwner,
		key: Any,
		cachedTokens: Long,
		uncachedTokens: Long,
		completionTokens: Long,
		totalTokens: Long,
	): Boolean {
		awaitSchema()
		val now = System.currentTimeMillis()
		return try {
			database.execute(
				"""
				INSERT INTO ${owner.table} (
					${owner.keyColumn}, cached_tokens, uncached_tokens, completion_tokens, total_tokens, request_count, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?, 1, ?, ?)
				ON DUPLICATE KEY UPDATE
					cached_tokens = cached_tokens + ?,
					uncached_tokens = uncached_tokens + ?,
					completion_tokens = completion_tokens + ?,
					total_tokens = total_tokens + ?,
					request_count = request_count + 1,
					updated_at = ?
				""".trimIndent(),
				listOf(
					if (key is String) key.take(128) else key,
					cachedTokens, uncachedTokens, completionTokens, totalTokens, now, now,
					cachedTokens, uncachedTokens, completionTokens, totalTokens, now,
				),
			)
			true
		} catch (e: Exception) {
			println("Failed to record ${owner.kind} token stats for $key: ${e.message}")
			false
		}
	}

	override suspend fun getGroupStats(groupName: String): LLMGroupTokenStats? {
		if (groupName.isBlank()) return null
		awaitSchema()
		return database.one(
			"""
			SELECT group_name, cached_tokens, uncached_tokens, completion_tokens, total_tokens, request_count, created_at, updated_at
			FROM llm_group_token_stats
			WHERE group_name = ?
			""".trimIndent(),
			listOf(groupName.take(128)),
		) { row ->
			groupStats(row, groupName)
		}
	}

	override suspend fun listGroupStats(limit: Int): List<LLMGroupTokenStats> {
		awaitSchema()
		return database.all(
			"""
			SELECT group_name, cached_tokens, uncached_tokens, completion_tokens, total_tokens, request_count, created_at, updated_at
			FROM llm_group_token_stats
			ORDER BY total_tokens DESC
			LIMIT ?
			""".trimIndent(),
			listOf(limit.coerceIn(1, 1000)),
		) { row ->
			groupStats(row, "")
		}
	}

	override suspend fun getUserStats(qqUid: Long): LLMUserTokenStats? {
		if (qqUid <= 0) return null
		awaitSchema()
		return database.one(
			"""
			SELECT qq_uid, cached_tokens, uncached_tokens, completion_tokens, total_tokens, request_count, created_at, updated_at
			FROM llm_user_token_stats
			WHERE qq_uid = ?
			""".trimIndent(),
			listOf(qqUid),
		) { row ->
			userStats(row)
		}
	}

	override suspend fun listUserStats(limit: Int): List<LLMUserTokenStats> {
		awaitSchema()
		return database.all(
			"""
			SELECT qq_uid, cached_tokens, uncached_tokens, completion_tokens, total_tokens, request_count, created_at, updated_at
			FROM llm_user_token_stats
			ORDER BY total_tokens DESC
			LIMIT ?
			""".trimIndent(),
			listOf(limit.coerceIn(1, 1000)),
		) { row ->
			userStats(row)
		}
	}

	private enum class TokenStatsOwner(val keyColumn: String, val keyType: String) {
		GROUP("group_name", "VARCHAR(128)"),
		USER("qq_uid", "BIGINT");

		val kind = name.lowercase()
		val table = "llm_${kind}_token_stats"
	}

	private fun groupStats(row: Row, fallbackName: String): LLMGroupTokenStats = LLMGroupTokenStats(
		groupName = row.get("group_name", String::class.java) ?: fallbackName,
		cachedTokens = number(row.get("cached_tokens")),
		uncachedTokens = number(row.get("uncached_tokens")),
		completionTokens = number(row.get("completion_tokens")),
		totalTokens = number(row.get("total_tokens")),
		requestCount = number(row.get("request_count")),
		createdAt = number(row.get("created_at")),
		updatedAt = number(row.get("updated_at")),
	)

	private fun userStats(row: Row): LLMUserTokenStats = LLMUserTokenStats(
		qqUid = number(row.get("qq_uid")),
		cachedTokens = number(row.get("cached_tokens")),
		uncachedTokens = number(row.get("uncached_tokens")),
		completionTokens = number(row.get("completion_tokens")),
		totalTokens = number(row.get("total_tokens")),
		requestCount = number(row.get("request_count")),
		createdAt = number(row.get("created_at")),
		updatedAt = number(row.get("updated_at")),
	)

	private fun number(value: Any?): Long = when (value) {
		is Number -> value.toLong()
		is String -> value.toLongOrNull() ?: 0L
		else -> 0L
	}
}
