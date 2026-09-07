package org.qo.services.llmServices

import org.springframework.stereotype.Service

@Service
class LLMTokenStatisticsService(
	private val repository: LLMTokenStatsRepository,
) {
	internal suspend fun recordUsage(
		groupName: String?,
		qqUid: Long,
		usage: LLMServices.Usage?,
	) {
		if (usage == null) return
		val cached = (usage.cacheHitTokens ?: 0).toLong().coerceAtLeast(0)
		val prompt = (usage.promptTokens ?: 0).toLong().coerceAtLeast(0)
		val uncached = (usage.cacheMissTokens?.toLong() ?: (prompt - cached)).coerceAtLeast(0)
		val completion = (usage.completionTokens ?: 0).toLong().coerceAtLeast(0)
		val total = (usage.totalTokens?.toLong() ?: (cached + uncached + completion)).coerceAtLeast(0)

		val cleanGroupName = groupName?.trim()?.takeIf { it.isNotBlank() }
		if (cleanGroupName != null) {
			repository.recordGroupTokens(
				groupName = cleanGroupName,
				cachedTokens = cached,
				uncachedTokens = uncached,
				completionTokens = completion,
				totalTokens = total,
			)
		}

		if (qqUid > 0) {
			repository.recordUserTokens(
				qqUid = qqUid,
				cachedTokens = cached,
				uncachedTokens = uncached,
				completionTokens = completion,
				totalTokens = total,
			)
		}
	}

	suspend fun getGroupStats(groupName: String): LLMGroupTokenStats? =
		repository.getGroupStats(groupName.trim())

	suspend fun listGroupStats(limit: Int = 100): List<LLMGroupTokenStats> =
		repository.listGroupStats(limit)

	suspend fun getUserStats(qqUid: Long): LLMUserTokenStats? =
		repository.getUserStats(qqUid)

	suspend fun listUserStats(limit: Int = 100): List<LLMUserTokenStats> =
		repository.listUserStats(limit)
}
