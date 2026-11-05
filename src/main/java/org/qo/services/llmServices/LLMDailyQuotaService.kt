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
	val groupLimit: Int? = null,
	val groupUsed: Int? = null,
	val groupRemaining: Int? = null,
	val groupChargedUnits: Int? = null,
)

data class LLMQuotaReservation(
	val quotaKey: String,
	val requestKey: String,
	val expiresAtEpochSeconds: Long,
	val view: LLMQuotaView,
	val qqUid: Long = 0,
	val reservedUnits: Int = 1,
	val groupId: Long? = null,
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
	val groupUsed: Int? = null,
	val groupLimit: Int? = null,
)

data class CreditDeductionResult(
	val success: Boolean,
	val remainingCredits: Int,
	val weeklyDeducted: Int = 0,
	val paidDeducted: Int = 0,
	val groupDeducted: Int = 0,
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
	suspend fun groupUsed(groupId: Long, period: String): Int? = 0
	suspend fun reserveSubsidy(source: String, summary: LLMSummaryConfig, estimate: java.math.BigDecimal): String? =
		null

	suspend fun settleSubsidy(id: String, cost: java.math.BigDecimal?) {}
	suspend fun retain(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) {}
	suspend fun balance(qqUid: Long): Int = 0
	suspend fun deductCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "image"): CreditDeductionResult =
		deductQuota(qqUid, "all", 0, amount, referenceId, kind)
	suspend fun refundCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "refund"): Boolean =
		refundQuota(qqUid, "all", 0, amount, referenceId, kind)
	suspend fun deductQuota(
		qqUid: Long,
		period: String,
		limit: Int,
		amount: Int,
		referenceId: String,
		kind: String = "image",
		groupId: Long? = null,
		groupLimit: Int = 0,
	): CreditDeductionResult = CreditDeductionResult(true, 0, 0, 0)
	suspend fun refundQuota(
		qqUid: Long,
		period: String,
		weeklyAmount: Int,
		paidAmount: Int,
		referenceId: String,
		kind: String = "refund",
		groupId: Long? = null,
		groupAmount: Int = 0,
	): Boolean = true
	suspend fun reserveUnits(
		principal: LLMPrincipal, quotaKey: String, requestKey: String, limit: Int,
		expiresAt: Long, units: Int, mode: String, provider: String, model: String, estimatedCost: java.math.BigDecimal,
		groupId: Long? = null, groupLimit: Int = 0,
	): LLMQuotaStoreDecision? =
		reserve(quotaKey, requestKey, limit, expiresAt)

	suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView? = null
	suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage, groupLimit: Int): LLMQuotaView? =
		settle(reservation, usage)
}

/** The historical class name is retained for injection compatibility; all windows are weekly. */
@Service
open class LLMDailyQuotaService @Autowired constructor(
	private val store: LLMQuotaStore,
	@Value("\${qapi.llm.weekly-limit:240}") configuredDailyLimit: Int,
	@Value("\${qapi.llm.guest-weekly-limit:160}") configuredGuestDailyLimit: Int = 160,
	@Value("\${qapi.llm.quota-zone:Asia/Shanghai}") quotaZoneName: String = "Asia/Shanghai",
	@Value("\${qapi.llm.promotion.multiplier:4}") configuredPromotionMultiplier: Int = 4,
	@Value("\${qapi.llm.promotion.ends-at:2026-11-01T23:59:59+08:00}") configuredPromotionEndsAt: String = "2026-11-01T23:59:59+08:00",
	@Value("\${qapi.llm.group-weekly-limit:500}") configuredGroupWeeklyLimit: Int = 500,
	@Value("\${qapi.llm.pilot-quota-groups:none}") configuredPilotQuotaGroups: String = System.getenv("LLM_PILOT_QUOTA_GROUP_IDS") ?: "none",
) {
	val weeklyLimit = configuredDailyLimit.coerceAtLeast(1)
	val guestWeeklyLimit = configuredGuestDailyLimit.coerceAtLeast(1)
	val groupWeeklyLimit = configuredGroupWeeklyLimit.coerceAtLeast(0)
	private val pilotGroupsAcceptAll = configuredPilotQuotaGroups.trim() == "*"
	private val pilotGroupIds: Set<Long> = if (pilotGroupsAcceptAll) emptySet() else {
		configuredPilotQuotaGroups.split(',', '，', ';', '；', ' ')
			.mapNotNull { it.trim().toLongOrNull() }
			.toSet()
	}
	private val quotaZone = ZoneId.of(quotaZoneName)
	private val promotionMultiplier = configuredPromotionMultiplier.coerceAtLeast(1)
	private val promotionEndsAtExclusive = OffsetDateTime.parse(configuredPromotionEndsAt).toInstant().plusSeconds(1)

	constructor(store: LLMQuotaStore, configuredDailyLimit: Int, quotaZoneName: String) :
			this(store, configuredDailyLimit, 160, quotaZoneName)

	fun isGroupQuotaEnabled(groupId: Long?): Boolean =
		groupId != null && groupId > 0 && groupWeeklyLimit > 0 && (pilotGroupsAcceptAll || groupId in pilotGroupIds)

	fun effectiveGroupLimit(groupId: Long?, now: Instant = Instant.now()): Int {
		if (!isGroupQuotaEnabled(groupId)) return 0
		val multiplier = if (promotionMultiplier > 1 && now.isBefore(promotionEndsAtExclusive)) promotionMultiplier else 1
		return (groupWeeklyLimit.toLong() * multiplier).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
	}

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
		provider: String = "", model: String = "", estimatedCost: java.math.BigDecimal = COST_PER_UNIT,
		groupId: Long? = null,
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
		val groupLimit = effectiveGroupLimit(groupId, now)
		val enabledGroupId = if (isGroupQuotaEnabled(groupId)) groupId else null

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
			estimatedCost,
			enabledGroupId,
			groupLimit,
		)
			?: return LLMQuotaDecision(LLMQuotaStatus.UNAVAILABLE, view(0, limit, reset, 0))
		val groupRemaining = if (result.groupLimit != null && result.groupUsed != null) {
			(result.groupLimit - result.groupUsed).coerceAtLeast(0)
		} else null
		val view = view(
			used = result.used,
			limit = limit,
			reset = reset,
			paid = result.paidCredits,
			groupLimit = result.groupLimit,
			groupUsed = result.groupUsed,
			groupRemaining = groupRemaining,
		)
		return LLMQuotaDecision(
			result.status, view, if (result.status == LLMQuotaStatus.ACCEPTED)
				LLMQuotaReservation(
					quotaKey = key,
					requestKey = requestKey,
					expiresAtEpochSeconds = reset,
					view = view,
					qqUid = principal.qqUid,
					reservedUnits = estimatedUnits,
					groupId = enabledGroupId,
				) else null
		)
	}

	suspend fun snapshot(qqUid: Long, now: Instant = Instant.now()): LLMQuotaDecision =
		snapshot(qqUid, true, now, null)

	suspend fun snapshot(
		qqUid: Long,
		hasAccount: Boolean,
		now: Instant = Instant.now(),
		groupId: Long? = null,
	): LLMQuotaDecision {
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
			val groupEnabled = isGroupQuotaEnabled(groupId)
			val groupLimit = if (groupEnabled) effectiveGroupLimit(groupId, now) else null
			val groupUsed = if (groupEnabled && groupId != null) {
				store.groupUsed(groupId, period.toString()) ?: 0
			} else null
			val groupRemaining = if (groupLimit != null && groupUsed != null) {
				(groupLimit - groupUsed).coerceAtLeast(0)
			} else null

			val isAccepted = if (groupRemaining != null && groupRemaining > 0) {
				true
			} else {
				used < limit || paid > 0
			}

			val status = if (isAccepted) LLMQuotaStatus.ACCEPTED else LLMQuotaStatus.EXCEEDED
			val view = view(
				used = used,
				limit = limit,
				reset = reset,
				paid = paid,
				groupLimit = groupLimit,
				groupUsed = groupUsed,
				groupRemaining = groupRemaining,
			)
			LLMQuotaDecision(status, view)
		} catch (_: Exception) {
			LLMQuotaDecision(LLMQuotaStatus.UNAVAILABLE, view(0, limit, reset, 0))
		}
	}

	suspend fun snapshot(qqUid: Long, groupId: Long?): LLMQuotaDecision =
		snapshot(qqUid, hasAccount = true, now = Instant.now(), groupId = groupId)

	suspend fun refundUsage(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) =
		store.refundUsage(reservation, usage)

	suspend fun refund(reservation: LLMQuotaReservation): Boolean = store.refund(reservation) != null
	open suspend fun deductCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "image"): CreditDeductionResult =
		deductQuota(qqUid, amount, referenceId)
	open suspend fun refundCredits(qqUid: Long, amount: Int, referenceId: String, kind: String = "refund"): Boolean =
		refundQuota(qqUid, 0, amount, referenceId)
	open suspend fun deductQuota(
		qqUid: Long,
		amount: Int,
		referenceId: String,
		hasAccount: Boolean = true,
		now: Instant = Instant.now(),
		groupId: Long? = null,
	): CreditDeductionResult {
		val limit = effectiveLimit(hasAccount, now)
		val p = period(now).toString()
		val groupLimit = effectiveGroupLimit(groupId, now)
		val enabledGroupId = if (isGroupQuotaEnabled(groupId)) groupId else null
		return store.deductQuota(
			qqUid = qqUid,
			period = p,
			limit = limit,
			amount = amount,
			referenceId = referenceId,
			kind = "image",
			groupId = enabledGroupId,
			groupLimit = groupLimit,
		)
	}
	open suspend fun refundQuota(
		qqUid: Long,
		weeklyAmount: Int,
		paidAmount: Int,
		referenceId: String,
		now: Instant = Instant.now(),
		groupId: Long? = null,
		groupAmount: Int = 0,
	): Boolean {
		val p = period(now).toString()
		return store.refundQuota(
			qqUid = qqUid,
			period = p,
			weeklyAmount = weeklyAmount,
			paidAmount = paidAmount,
			referenceId = referenceId,
			kind = "refund",
			groupId = groupId,
			groupAmount = groupAmount,
		)
	}
	suspend fun reserveSubsidy(source: String, summary: LLMSummaryConfig, estimate: java.math.BigDecimal) =
		store.reserveSubsidy(source, summary, estimate)

	suspend fun settleSubsidy(id: String, cost: java.math.BigDecimal?) = store.settleSubsidy(id, cost)
	suspend fun retain(reservation: LLMQuotaReservation, usage: AiQuotaUsage?) = store.retain(reservation, usage)
	suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView {
		val groupLimit = reservation.view.groupLimit ?: effectiveGroupLimit(reservation.groupId)
		return requireNotNull(
			store.settle(
				reservation,
				usage,
				groupLimit,
			)
		) { "Quota settlement unavailable; reservation retained for reconciliation" }
	}

	fun period(now: Instant): LocalDate = now.atZone(quotaZone).toLocalDate()
		.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

	private fun quotaKey(uid: Long, period: LocalDate) = "llm:weekly:$period:$uid"
	private fun view(
		used: Int,
		limit: Int,
		reset: Long,
		paid: Int,
		chargedUnits: Int? = null,
		groupLimit: Int? = null,
		groupUsed: Int? = null,
		groupRemaining: Int? = null,
		groupChargedUnits: Int? = null,
	) = LLMQuotaView(
		limit = limit,
		used = used.coerceAtLeast(0),
		remaining = limit - used.coerceAtLeast(0),
		resetAtEpochSeconds = reset,
		paidCredits = paid,
		chargedUnits = chargedUnits,
		groupLimit = groupLimit,
		groupUsed = groupUsed?.coerceAtLeast(0),
		groupRemaining = groupRemaining,
		groupChargedUnits = groupChargedUnits,
	)

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
