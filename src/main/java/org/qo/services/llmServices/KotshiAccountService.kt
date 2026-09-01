package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.orm.UserORM
import org.qo.services.loginService.KotshiPrivacyService
import org.qo.services.loginService.KotshiPrivacySettings
import org.qo.services.loginService.Login
import org.qo.services.loginService.QqLoginService
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

data class KotshiUsageSummary(
	val requests: Long = 0,
	val completed: Long = 0,
	val failed: Long = 0,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val totalTokens: Long = 0,
)

data class KotshiUsageRecord(
	val model: String,
	val status: String,
	val totalTokens: Long,
	val createdAt: Long,
	val completedAt: Long?,
)

data class KotshiAccountSnapshot(
	val queryEnabled: Boolean,
	val quota: LLMQuotaView,
	val usage: KotshiUsageSummary,
	val recentUsage: List<KotshiUsageRecord>,
) {
	fun toJson(): String = JsonObject().apply {
		addProperty("kotshi_query_enabled", queryEnabled)
		add("quota", JsonObject().apply {
			addProperty("limit", quota.limit)
			addProperty("used", quota.used)
			addProperty("remaining", quota.remaining)
			addProperty("reset_at", quota.resetAtEpochSeconds)
		})
		add("usage", JsonObject().apply {
			addProperty("requests", usage.requests)
			addProperty("completed", usage.completed)
			addProperty("failed", usage.failed)
			addProperty("prompt_tokens", usage.promptTokens)
			addProperty("completion_tokens", usage.completionTokens)
			addProperty("total_tokens", usage.totalTokens)
		})
		add("recent_usage", JsonArray().apply {
			recentUsage.forEach { record ->
				add(JsonObject().apply {
					addProperty("model", record.model)
					addProperty("status", record.status)
					addProperty("total_tokens", record.totalTokens)
					addProperty("created_at", record.createdAt)
					record.completedAt?.let { addProperty("completed_at", it) }
				})
			}
		})
	}.toString()
}

/** Aggregates only the authenticated user's Kotshi/Web requests and shared quota. */
@Service
class KotshiAccountService(
	private val login: Login,
	private val privacyService: KotshiPrivacyService,
	private val dailyQuotaService: LLMDailyQuotaService,
	private val database: ReactiveDatabase,
) {
	private val userORM = UserORM()
	private val schemaMutex = Mutex()
	@Volatile
	private var schemaReady = false
	private val quotaZone = ZoneId.of("Asia/Shanghai")

	suspend fun snapshot(token: String): KotshiAccountSnapshot? {
		val user = authenticatedUser(token) ?: return null
		val quota = dailyQuotaService.snapshot(user.uid, hasAccount = true).view
		val settings = privacyService.settings(user.username) ?: return null
		val (usage, recentUsage) = loadUsage(user.uid)
		return KotshiAccountSnapshot(
			queryEnabled = settings.queryEnabled,
			quota = quota,
			usage = usage,
			recentUsage = recentUsage,
		)
	}

	suspend fun updateQueryEnabled(token: String, enabled: Boolean): KotshiPrivacySettings? {
		val user = authenticatedUser(token) ?: return null
		return privacyService.update(user.username, enabled)
	}

	private suspend fun authenticatedUser(token: String): org.qo.datas.Mapping.Users? {
		val (identity, errorCode) = login.validate(token)
		if (errorCode != 0 || identity.isNullOrBlank()) return null
		val user = if (identity.startsWith(QqLoginService.GUEST_PREFIX)) {
			val qqUid = identity.removePrefix(QqLoginService.GUEST_PREFIX).toLongOrNull()
			if (qqUid != null) userORM.readAsync(qqUid) else null
		} else {
			userORM.readAsync(identity)
		}
		return user?.takeIf { it.frozen != true }
	}

	private suspend fun loadUsage(uid: Long): Pair<KotshiUsageSummary, List<KotshiUsageRecord>> {
		return runCatching {
			ensureSchema()
			val dayStart = LocalDate.now(quotaZone)
				.atStartOfDay(quotaZone)
				.toInstant()
				.toEpochMilli()
			val summary = database.one(
				"""
				SELECT COUNT(*) AS requests,
				       SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS completed,
				       SUM(CASE WHEN status IN ('failed', 'rejected') THEN 1 ELSE 0 END) AS failed,
				       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
				       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
				       COALESCE(SUM(total_tokens), 0) AS total_tokens
				FROM llm_access_records
				WHERE uid = ? AND source = 'web' AND created_at >= ?
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
			val recent = database.all(
				"""
				SELECT model, status, total_tokens, created_at, completed_at
				FROM llm_access_records
				WHERE uid = ? AND source = 'web' AND created_at >= ?
				ORDER BY created_at DESC
				LIMIT 20
				""".trimIndent(),
				listOf(uid, dayStart),
			) { row ->
				KotshiUsageRecord(
					model = row.get("model", String::class.java) ?: "fast",
					status = row.get("status", String::class.java) ?: "unknown",
					totalTokens = number(row.get("total_tokens")),
					createdAt = number(row.get("created_at")),
					completedAt = row.get("completed_at")?.let(::number),
				)
			}
			summary to recent
		}.getOrElse { error ->
			println("[Kotshi] usage lookup failed: ${error.message}")
			KotshiUsageSummary() to emptyList()
		}
	}

	private suspend fun ensureSchema() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute(
				"""
				CREATE TABLE IF NOT EXISTS llm_access_records (
					id BIGINT AUTO_INCREMENT PRIMARY KEY,
					uid BIGINT NOT NULL,
					username VARCHAR(128) NOT NULL,
					source VARCHAR(32) NOT NULL DEFAULT 'unknown',
					source_identity VARCHAR(128) NULL,
					request_id VARCHAR(80) NOT NULL,
					model VARCHAR(128) NOT NULL,
					stream BOOLEAN NOT NULL,
					status VARCHAR(32) NOT NULL,
					prompt_tokens INT NULL,
					completion_tokens INT NULL,
					total_tokens INT NULL,
					error_message VARCHAR(512) NULL,
					created_at BIGINT NOT NULL,
					completed_at BIGINT NULL,
					INDEX idx_llm_access_uid_created (uid, created_at)
				)
				""".trimIndent(),
			)
			ensureColumn("source", "VARCHAR(32) NOT NULL DEFAULT 'unknown'")
			ensureColumn("source_identity", "VARCHAR(128) NULL")
			schemaReady = true
		}
	}

	private suspend fun ensureColumn(name: String, definition: String) {
		val exists = database.one(
			"""
			SELECT 1 FROM information_schema.columns
			WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
			  AND LOWER(table_name) = 'llm_access_records'
			  AND LOWER(column_name) = LOWER(?)
			LIMIT 1
			""".trimIndent(),
			listOf(name),
		) { true } != null
		if (!exists) database.execute("ALTER TABLE llm_access_records ADD COLUMN $name $definition")
	}

	private fun number(value: Any?): Long = when (value) {
		is Number -> value.toLong()
		is String -> value.toLongOrNull() ?: 0L
		else -> 0L
	}
}
