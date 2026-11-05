package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.LlmAccessRecordDbRepository
import org.qo.orm.UserORM
import org.qo.services.loginService.KotshiPrivacyService
import org.qo.services.loginService.KotshiPrivacySettings
import org.qo.services.loginService.Login
import org.qo.services.loginService.QqLoginService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId

data class KotshiUsageSummary(
	val requests: Long = 0,
	val completed: Long = 0,
	val failed: Long = 0,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val totalTokens: Long = 0,
)

data class KotshiChannelSummary(
	val requests: Long = 0,
	val completed: Long = 0,
	val failed: Long = 0,
	val totalTokens: Long = 0,
)

data class KotshiUsageRecord(
	val source: String,
	val status: String,
	val totalTokens: Long,
	val createdAt: Long,
	val completedAt: Long?,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val errorMessage: String? = null,
) {
	constructor(source: String, status: String, totalTokens: Long, createdAt: Long, completedAt: Long?) :
		this(source, status, totalTokens, createdAt, completedAt, 0, 0, null)
}

data class KotshiAccountSnapshot(
	val queryEnabled: Boolean,
	val activeResponseEnabled: Boolean = true,
	val quota: LLMQuotaView,
	val usage: KotshiUsageSummary,
	val recentUsage: List<KotshiUsageRecord>,
	val byChannel: Map<String, KotshiChannelSummary> = emptyMap(),
) {
	constructor(
		queryEnabled: Boolean,
		quota: LLMQuotaView,
		usage: KotshiUsageSummary,
		recentUsage: List<KotshiUsageRecord>,
	) : this(queryEnabled, true, quota, usage, recentUsage, emptyMap())

	fun toJson(): String = JsonObject().apply {
		addProperty("kotshi_query_enabled", queryEnabled)
		addProperty("active_response_enabled", activeResponseEnabled)
		addProperty("qbot_active_response_enabled", activeResponseEnabled)
		add("quota", JsonObject().apply {
			addProperty("limit", quota.limit)
			addProperty("used", quota.used)
			addProperty("remaining", quota.remaining)
			addProperty("reset_at", quota.resetAtEpochSeconds)
			addProperty("paid_credits", quota.paidCredits)
			addProperty("period", "weekly")
		})
		add("usage", JsonObject().apply {
			addProperty("requests", usage.requests)
			addProperty("completed", usage.completed)
			addProperty("failed", usage.failed)
			addProperty("prompt_tokens", usage.promptTokens)
			addProperty("completion_tokens", usage.completionTokens)
			addProperty("total_tokens", usage.totalTokens)
		})
		add("by_channel", JsonObject().apply {
			byChannel.forEach { (channel, summary) ->
				add(channel, JsonObject().apply {
					addProperty("requests", summary.requests)
					addProperty("completed", summary.completed)
					addProperty("failed", summary.failed)
					addProperty("total_tokens", summary.totalTokens)
				})
			}
		})
		add("recent_usage", JsonArray().apply {
			recentUsage.forEach { record ->
				add(JsonObject().apply {
					addProperty("source", record.source)
					addProperty("status", record.status)
					addProperty("total_tokens", record.totalTokens)
					addProperty("prompt_tokens", record.promptTokens)
					addProperty("completion_tokens", record.completionTokens)
					addProperty("created_at", record.createdAt)
					record.completedAt?.let { addProperty("completed_at", it) }
					record.errorMessage?.let { addProperty("error_message", it) }
				})
			}
		})
	}.toString()
}

/** Aggregates the authenticated user's requests across all channels and shared quota. */
@Service
class KotshiAccountService @Autowired constructor(
	private val login: Login,
	private val privacyService: KotshiPrivacyService,
	private val dailyQuotaService: LLMDailyQuotaService,
	private val repository: LlmAccessRecordDbRepository,
) {
	constructor(
		login: Login,
		privacyService: KotshiPrivacyService,
		dailyQuotaService: LLMDailyQuotaService,
		database: ReactiveDatabase,
		accessRecordSchema: LLMAccessRecordSchema,
	) : this(login, privacyService, dailyQuotaService, LlmAccessRecordDbRepository(database))

	private val userORM = UserORM()
	private val quotaZone = ZoneId.of("Asia/Shanghai")

	suspend fun snapshot(token: String): KotshiAccountSnapshot? {
		val user = authenticatedUser(token) ?: return null
		val quota = dailyQuotaService.snapshot(user.uid, hasAccount = true).view
		val settings = privacyService.settings(user.username) ?: return null
		val (usage, recentUsage, byChannel) = loadUsageWithChannels(user.uid)
		return KotshiAccountSnapshot(
			queryEnabled = settings.queryEnabled,
			activeResponseEnabled = settings.activeResponseEnabled,
			quota = quota,
			usage = usage,
			recentUsage = recentUsage,
			byChannel = byChannel,
		)
	}

	suspend fun updateQueryEnabled(token: String, enabled: Boolean): KotshiPrivacySettings? {
		val user = authenticatedUser(token) ?: return null
		return privacyService.update(user.username, enabled)
	}

	suspend fun updateSettings(
		token: String,
		queryEnabled: Boolean? = null,
		activeResponseEnabled: Boolean? = null,
	): KotshiPrivacySettings? {
		val user = authenticatedUser(token) ?: return null
		return privacyService.updateSettings(user.username, queryEnabled, activeResponseEnabled)
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

	internal suspend fun loadUsage(uid: Long, now: Instant = Instant.now()): Pair<KotshiUsageSummary, List<KotshiUsageRecord>> {
		val (summary, recent, _) = loadUsageWithChannels(uid, now)
		return summary to recent
	}

	internal suspend fun loadUsageWithChannels(
		uid: Long,
		now: Instant = Instant.now(),
	): Triple<KotshiUsageSummary, List<KotshiUsageRecord>, Map<String, KotshiChannelSummary>> {
		return runCatching {
			val dayStart = now.atZone(quotaZone).toLocalDate()
				.atStartOfDay(quotaZone)
				.toInstant()
				.toEpochMilli()
			val summary = repository.loadUsageSummary(uid, dayStart)
			val recent = repository.loadRecentUsage(uid, 20)
			val byChannel = repository.loadChannelSummary(uid, dayStart)
			Triple(summary, recent, byChannel)
		}.getOrElse { error ->
			if (error is kotlinx.coroutines.CancellationException) throw error
			println("[Kotshi] usage lookup failed: ${error.message}")
			Triple(KotshiUsageSummary(), emptyList(), emptyMap())
		}
	}
}
