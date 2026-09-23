package org.qo.services.llmServices

import com.google.gson.JsonObject
import org.qo.redis.DatabaseType

internal fun LLMServices.reserveRequest(qqUid: Long): Boolean {
	return redis.setIfAbsentWithExpire("llm:req:$qqUid", "1", DatabaseType.QO_ASSISTANT_DATABASE.value, 2)
		.ignoreException() ?: true
}

internal suspend fun LLMServices.reserveQuota(
	principal: LLMPrincipal,
	clientRequestId: String?,
	request: LLMServices.NormalizedRequest,
	provider: LLMProvider,
): LLMQuotaDecision {
	val logger = org.slf4j.LoggerFactory.getLogger(LLMServices::class.java)
	return try {
		val pricing = request.pricing ?: provider.modelConfig(request.preset).pricing?.at(java.time.Instant.now())
		if (pricing == null) {
			logger.error(
				"AI model pricing missing: provider={}, mode={}, model={}; configure models.{}.pricing in providers.json",
				provider.name, request.preset, request.model, request.preset
			)
			return LLMQuotaDecision(LLMQuotaStatus.PRICING_UNAVAILABLE, dailyQuotaService.snapshot(principal.qqUid, principal.hasAccount).view)
		}
		val admissionCost = LLMDailyQuotaService.COST_PER_UNIT
		dailyQuotaService.reserve(
			principal,
			clientRequestId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
			estimatedUnits = 1,
			mode = request.preset,
			provider = provider.name,
			model = request.model,
			estimatedCost = admissionCost
		)
	} catch (error: Exception) {
		if (error is kotlinx.coroutines.CancellationException) throw error
		val sqlError = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<io.r2dbc.spi.R2dbcException>().firstOrNull()
		logger.warn(
			"AI quota reservation failed: source={}, uid={}, provider={}, mode={}, error={}, sqlState={}, sqlCode={}",
			principal.source.value, principal.qqUid, provider.name, request.preset, error.javaClass.simpleName, sqlError?.sqlState, sqlError?.errorCode
		)
		LLMQuotaDecision(LLMQuotaStatus.UNAVAILABLE, dailyQuotaService.snapshot(principal.qqUid, principal.hasAccount).view)
	}
}

internal fun LLMServices.quotaFailure(decision: LLMQuotaDecision, principal: LLMPrincipal): LLMNonStreamResult? {
	val (status, code) = when (decision.status) {
		LLMQuotaStatus.ACCEPTED -> return null
		LLMQuotaStatus.EXCEEDED -> 429 to "weekly_quota_exceeded"
		LLMQuotaStatus.RATE_LIMITED -> 429 to "rate_limited"
		LLMQuotaStatus.DUPLICATE -> 409 to "duplicate_request"
		LLMQuotaStatus.UNAVAILABLE -> 503 to "quota_unavailable"
		LLMQuotaStatus.PRICING_UNAVAILABLE -> 503 to "pricing_unavailable"
	}
	return LLMNonStreamResult(status, errorJson(code, quotaErrorMessage(decision.status, principal)), decision.view)
}

internal fun LLMServices.quotaStreamFailure(decision: LLMQuotaDecision, principal: LLMPrincipal): LLMStreamResult? {
	val failure = quotaFailure(decision, principal) ?: return null
	return LLMStreamResult(failure.status, flowOfText(failure.body), failure.quota)
}

internal fun LLMServices.quotaErrorMessage(status: LLMQuotaStatus, principal: LLMPrincipal): String = when (status) {
	LLMQuotaStatus.ACCEPTED -> ""
	LLMQuotaStatus.EXCEEDED -> if (principal.hasAccount) {
		"本周额度和 Paid Credits 不足，可购买 Credits 继续使用"
	} else {
		"本周 QQ 额度为 ${dailyQuotaService.guestWeeklyLimit} Units，注册后同一身份提升至 ${dailyQuotaService.weeklyLimit} Units；可购买 Credits 继续使用"
	}
	LLMQuotaStatus.RATE_LIMITED -> "已达到并发限制，请稍后重试"
	LLMQuotaStatus.DUPLICATE -> "该请求已经提交，请勿重复发送"
	LLMQuotaStatus.UNAVAILABLE -> "额度服务暂时不可用，请稍后重试"
	LLMQuotaStatus.PRICING_UNAVAILABLE -> "模型计价未配置，请联系管理员"
}

internal fun LLMServices.quotaJson(view: LLMQuotaView): String = JsonObject().apply {
	addProperty("limit", view.limit)
	addProperty("used", view.used)
	addProperty("remaining", view.remaining)
	addProperty("reset_at", view.resetAtEpochSeconds)
	addProperty("paid_credits", view.paidCredits)
	addProperty("period", "weekly")
	view.chargedUnits?.let { addProperty("charged_units", it) }
}.toString()
