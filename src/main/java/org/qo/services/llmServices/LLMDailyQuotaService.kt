package org.qo.services.llmServices

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

enum class LLMSource(val value: String) {
	WEB("web"),
	QQ("qq"),
	MINECRAFT("minecraft"),
}

data class LLMPrincipal(
	val qqUid: Long,
	val displayName: String,
	val source: LLMSource,
	val sourceIdentity: String,
	val hasAccount: Boolean = true,
)

enum class LLMQuotaStatus {
	ACCEPTED,
	EXCEEDED,
	DUPLICATE,
	UNAVAILABLE,
	PRICING_UNAVAILABLE,
	RATE_LIMITED,
}

data class LLMQuotaView(
	val limit: Int,
	val used: Int,
	val remaining: Int,
	val resetAtEpochSeconds: Long,
	val paidCredits: Int = 0,
	val chargedUnits: Int? = null,
)

data class LLMQuotaReservation(
	val quotaKey: String,
	val requestKey: String,
	val expiresAtEpochSeconds: Long,
	val view: LLMQuotaView,
	val qqUid: Long = 0,
	val reservedUnits: Int = 1,
)

data class LLMQuotaDecision(
	val status: LLMQuotaStatus,
	val view: LLMQuotaView,
	val reservation: LLMQuotaReservation? = null,
)

data class LLMQuotaStoreDecision(
	val status: LLMQuotaStatus,
	val used: Int,
	val paidCredits: Int = 0,
)

data class CreditDeductionResult(
	val success: Boolean,
	val remainingCredits: Int,
	val error: String? = null,
)

interface LLMQuotaStore {
	suspend fun reserve(
		quotaKey: String,
		requestKey: String,
		limit: Int,
		expiresAtEpochSeconds: Long
	): LLMQuotaStoreDecision?

	suspend fun refund(reservation: LLMQuotaReservation): Int?
	suspend fun refundUsage(reservation: LLMQuotaReservation, usage: AiQuotaUsage?): Int? = refund(reservation)
	suspend fun used(quotaKey: String): Int?
	suspend fun reserveSubsidy(source: String, summary: LLMSummaryConfig, estimate: java.math.BigDecimal): String? =
		null

	suspend fun settleSubsidy(id: String, cost: java.math.BigDecimal?) {}
	suspend fun retain(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) {}
	suspend fun balance(qqUid: Long): Int = 0
	suspend fun deductCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "image"): CreditDeductionResult =
		CreditDeductionResult(true, 0)
	suspend fun refundCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "refund"): Boolean = true
	suspend fun reserveUnits(
		principal: LLMPrincipal, quotaKey: String, requestKey: String, limit: Int,
		expiresAt: Long, units: Int, mode: String, provider: String, model: String, estimatedCost: java.math.BigDecimal
	): LLMQuotaStoreDecision? =
		reserve(quotaKey, requestKey, limit, expiresAt)

	suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView? = null
}

/** The historical class name is retained for injection compatibility; all windows are weekly. */
@Service
open class LLMDailyQuotaService @Autowired constructor(
	private val store: LLMQuotaStore,
	@Value("\${qapi.llm.weekly-limit:120}") configuredDailyLimit: Int,
	@Value("\${qapi.llm.guest-weekly-limit:80}") configuredGuestDailyLimit: Int = 80,
	@Value("\${qapi.llm.quota-zone:Asia/Shanghai}") quotaZoneName: String = "Asia/Shanghai",
	@Value("\${qapi.llm.promotion.multiplier:4}") configuredPromotionMultiplier: Int = 4,
	@Value("\${qapi.llm.promotion.ends-at:2026-11-01T23:59:59+08:00}") configuredPromotionEndsAt: String = "2026-11-01T23:59:59+08:00",
) {
	val weeklyLimit = configuredDailyLimit.coerceAtLeast(1)
	val guestWeeklyLimit = configuredGuestDailyLimit.coerceAtLeast(1)
	private val quotaZone = ZoneId.of(quotaZoneName)
	private val promotionMultiplier = configuredPromotionMultiplier.coerceAtLeast(1)
	private val promotionEndsAtExclusive = OffsetDateTime.parse(configuredPromotionEndsAt).toInstant().plusSeconds(1)

	constructor(store: LLMQuotaStore, configuredDailyLimit: Int, quotaZoneName: String) :
			this(store, configuredDailyLimit, 80, quotaZoneName)

	fun effectiveLimit(hasAccount: Boolean, now: Instant = Instant.now()): Int {
		val baseLimit = if (hasAccount) weeklyLimit else guestWeeklyLimit
		val multiplier = if (promotionMultiplier > 1 && now.isBefore(promotionEndsAtExclusive)) promotionMultiplier else 1
		return (baseLimit.toLong() * multiplier).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
	}

	fun weeklyLimits(now: Instant = Instant.now()): Pair<Int, Int> =
		effectiveLimit(false, now) to effectiveLimit(true, now)

	suspend fun reserve(
		principal: LLMPrincipal, requestId: String = UUID.randomUUID().toString(),
		now: Instant = Instant.now(), estimatedUnits: Int = 1, mode: String = "fast",
		provider: String = "", model: String = "", estimatedCost: java.math.BigDecimal = COST_PER_UNIT
	): LLMQuotaDecision {
		require(principal.qqUid > 0 && estimatedUnits > 0)
		val limit = effectiveLimit(principal.hasAccount, now)
		val period = period(now)
		val reset = period.plusWeeks(1).atStartOfDay(quotaZone).toEpochSecond()
		val key = quotaKey(principal.qqUid, period)
		val digest = MessageDigest.getInstance("SHA-256").digest(requestId.toByteArray(StandardCharsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
		// A QQ identity and request ID are shared across every entry point.
		val requestKey = "llm:weekly-request:${principal.qqUid}:$digest"
		val result = store.reserveUnits(
			principal,
			key,
			requestKey,
			limit,
			reset,
			estimatedUnits,
			mode,
			provider,
			model,
			estimatedCost
		)
			?: return LLMQuotaDecision(LLMQuotaStatus.UNAVAILABLE, view(0, limit, reset, 0))
		val view = view(result.used, limit, reset, result.paidCredits)
		return LLMQuotaDecision(
			result.status, view, if (result.status == LLMQuotaStatus.ACCEPTED)
				LLMQuotaReservation(key, requestKey, reset, view, principal.qqUid, estimatedUnits) else null
		)
	}

	suspend fun snapshot(qqUid: Long, now: Instant = Instant.now()): LLMQuotaDecision = snapshot(qqUid, true, now)
	suspend fun snapshot(qqUid: Long, hasAccount: Boolean, now: Instant = Instant.now()): LLMQuotaDecision {
		require(qqUid > 0)
		val limit = effectiveLimit(hasAccount, now)
		val period = period(now)
		val reset = period.plusWeeks(1).atStartOfDay(quotaZone).toEpochSecond()
		return try {
			val used = store.used(quotaKey(qqUid, period)) ?: return LLMQuotaDecision(
				LLMQuotaStatus.UNAVAILABLE,
				view(0, limit, reset, 0)
			)
			val paid = store.balance(qqUid)
			LLMQuotaDecision(
				if (used >= limit && paid <= 0) LLMQuotaStatus.EXCEEDED else LLMQuotaStatus.ACCEPTED,
				view(used, limit, reset, paid)
			)
		} catch (_: Exception) {
			LLMQuotaDecision(LLMQuotaStatus.UNAVAILABLE, view(0, limit, reset, 0))
		}
	}

	suspend fun refundUsage(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) =
		store.refundUsage(reservation, usage)

	suspend fun refund(reservation: LLMQuotaReservation): Boolean = store.refund(reservation) != null
	open suspend fun deductCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "image"): CreditDeductionResult =
		store.deductCredits(qqUid, amount, referenceId, kind)
	open suspend fun refundCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "refund"): Boolean =
		store.refundCredits(qqUid, amount, referenceId, kind)
	suspend fun reserveSubsidy(source: String, summary: LLMSummaryConfig, estimate: java.math.BigDecimal) =
		store.reserveSubsidy(source, summary, estimate)

	suspend fun settleSubsidy(id: String, cost: java.math.BigDecimal?) = store.settleSubsidy(id, cost)
	suspend fun retain(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) = store.retain(reservation, usage)
	suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView =
		requireNotNull(
			store.settle(
				reservation,
				usage
			)
		) { "Quota settlement unavailable; reservation retained for reconciliation" }

	fun period(now: Instant): LocalDate = now.atZone(quotaZone).toLocalDate()
		.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

	private fun quotaKey(uid: Long, period: LocalDate) = "llm:weekly:$period:$uid"
	private fun view(used: Int, limit: Int, reset: Long, paid: Int) =
		LLMQuotaView(limit, used.coerceAtLeast(0), limit - used.coerceAtLeast(0), reset, paid)

	companion object {
		val COST_PER_UNIT = java.math.BigDecimal("0.005")
		fun units(cost: java.math.BigDecimal): Int {
			require(cost.signum() >= 0)
			return cost.divide(COST_PER_UNIT, 0, java.math.RoundingMode.CEILING).intValueExact().coerceAtLeast(1)
		}
	}
}

data class AiQuotaUsage(
	val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
	val reasoningTokens: Long?, val actualCostCny: java.math.BigDecimal,
	val chargedUnits: Int, val conversationId: String? = null,
)
