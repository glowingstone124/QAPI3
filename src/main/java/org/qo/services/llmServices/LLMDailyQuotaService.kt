package org.qo.services.llmServices

import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
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
}

data class LLMQuotaView(
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val resetAtEpochSeconds: Long,
)

data class LLMQuotaReservation(
    val quotaKey: String,
    val requestKey: String,
    val expiresAtEpochSeconds: Long,
    val view: LLMQuotaView,
)

data class LLMQuotaDecision(
    val status: LLMQuotaStatus,
    val view: LLMQuotaView,
    val reservation: LLMQuotaReservation? = null,
)

data class LLMQuotaStoreDecision(
    val status: LLMQuotaStatus,
    val used: Int,
)

interface LLMQuotaStore {
    fun reserve(
        quotaKey: String,
        requestKey: String,
        limit: Int,
        expiresAtEpochSeconds: Long,
    ): LLMQuotaStoreDecision?

    fun refund(reservation: LLMQuotaReservation): Int?

    fun used(quotaKey: String): Int?
}

@Component
class RedisLLMQuotaStore : LLMQuotaStore {
    private val redis = Redis()
    private val database = DatabaseType.QO_RATE_LIMIT_DATABASE.value

    override fun reserve(
        quotaKey: String,
        requestKey: String,
        limit: Int,
        expiresAtEpochSeconds: Long,
    ): LLMQuotaStoreDecision? {
        val result = redis.reserveDailyQuota(
            quotaKey,
            requestKey,
            database,
            limit,
            expiresAtEpochSeconds,
        ).onException { error ->
            println("LLM daily quota reservation failed: ${error.message}")
        } ?: return null
        val status = when (result.status) {
            1L -> LLMQuotaStatus.ACCEPTED
            2L -> LLMQuotaStatus.DUPLICATE
            else -> LLMQuotaStatus.EXCEEDED
        }
        return LLMQuotaStoreDecision(status, result.used.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    override fun refund(reservation: LLMQuotaReservation): Int? = redis.refundDailyQuota(
        reservation.quotaKey,
        reservation.requestKey,
        database,
        reservation.expiresAtEpochSeconds,
    ).onException { error ->
        println("LLM daily quota refund failed: ${error.message}")
    }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

    override fun used(quotaKey: String): Int? = redis.readDailyQuota(quotaKey, database)
        .onException { error -> println("LLM daily quota lookup failed: ${error.message}") }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())
        ?.toInt()
}

@Service
class LLMDailyQuotaService(
    private val store: LLMQuotaStore,
    @Value("\${qapi.llm.daily-limit:50}") configuredDailyLimit: Int,
    @Value("\${qapi.llm.guest-daily-limit:20}") configuredGuestDailyLimit: Int = 20,
    @Value("\${qapi.llm.quota-zone:Asia/Shanghai}") quotaZoneName: String = "Asia/Shanghai",
) {
    val dailyLimit = configuredDailyLimit.coerceAtLeast(1)
    val guestDailyLimit = configuredGuestDailyLimit.coerceAtLeast(1)
    private val quotaZone = ZoneId.of(quotaZoneName)

    constructor(
        store: LLMQuotaStore,
        configuredDailyLimit: Int,
        quotaZoneName: String,
    ) : this(store, configuredDailyLimit, 20, quotaZoneName)

    fun effectiveLimit(hasAccount: Boolean): Int =
        if (hasAccount) dailyLimit else guestDailyLimit

    fun reserve(
        principal: LLMPrincipal,
        requestId: String = UUID.randomUUID().toString(),
        now: Instant = Instant.now(),
    ): LLMQuotaDecision {
        require(principal.qqUid > 0) { "QQ UID must be positive" }
        val limit = effectiveLimit(principal.hasAccount)
        val window = window(now)
        val quotaKey = quotaKey(principal.qqUid, window.date)
        val requestKey = requestKey(principal, requestId, window.date)
        val stored = store.reserve(quotaKey, requestKey, limit, window.expiresAtEpochSeconds)
            ?: return LLMQuotaDecision(
                LLMQuotaStatus.UNAVAILABLE,
                view(0, limit, window.resetAtEpochSeconds),
            )
        val quotaView = view(stored.used, limit, window.resetAtEpochSeconds)
        val reservation = if (stored.status == LLMQuotaStatus.ACCEPTED) {
            LLMQuotaReservation(quotaKey, requestKey, window.expiresAtEpochSeconds, quotaView)
        } else {
            null
        }
        return LLMQuotaDecision(stored.status, quotaView, reservation)
    }

    fun snapshot(qqUid: Long, now: Instant = Instant.now()): LLMQuotaDecision =
        snapshot(qqUid, true, now)

    fun snapshot(qqUid: Long, hasAccount: Boolean, now: Instant = Instant.now()): LLMQuotaDecision {
        require(qqUid > 0) { "QQ UID must be positive" }
        val limit = effectiveLimit(hasAccount)
        val window = window(now)
        val used = store.used(quotaKey(qqUid, window.date))
            ?: return LLMQuotaDecision(
                LLMQuotaStatus.UNAVAILABLE,
                view(0, limit, window.resetAtEpochSeconds),
            )
        val status = if (used >= limit) LLMQuotaStatus.EXCEEDED else LLMQuotaStatus.ACCEPTED
        return LLMQuotaDecision(status, view(used, limit, window.resetAtEpochSeconds))
    }

    fun refund(reservation: LLMQuotaReservation): Boolean = store.refund(reservation) != null

    private fun view(used: Int, limit: Int, resetAtEpochSeconds: Long): LLMQuotaView {
        val boundedUsed = used.coerceAtLeast(0)
        return LLMQuotaView(
            limit = limit,
            used = boundedUsed,
            remaining = (limit - boundedUsed).coerceAtLeast(0),
            resetAtEpochSeconds = resetAtEpochSeconds,
        )
    }

    private fun quotaKey(qqUid: Long, date: LocalDate): String = "llm:quota:$date:$qqUid"

    private fun requestKey(principal: LLMPrincipal, requestId: String, date: LocalDate): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "llm:quota-request:$date:${principal.qqUid}:${principal.source.value}:$digest"
    }

    private fun window(now: Instant): QuotaWindow {
        val current = now.atZone(quotaZone)
        val resetAt = current.toLocalDate().plusDays(1).atStartOfDay(quotaZone).toInstant()
        return QuotaWindow(
            date = current.toLocalDate(),
            resetAtEpochSeconds = resetAt.epochSecond,
            expiresAtEpochSeconds = resetAt.plusSeconds(EXPIRY_GRACE_SECONDS).epochSecond,
        )
    }

    private data class QuotaWindow(
        val date: LocalDate,
        val resetAtEpochSeconds: Long,
        val expiresAtEpochSeconds: Long,
    )

    private companion object {
        const val EXPIRY_GRACE_SECONDS = 300L
    }
}
