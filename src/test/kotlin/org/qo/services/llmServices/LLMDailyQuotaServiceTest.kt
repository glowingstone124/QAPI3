package org.qo.services.llmServices

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LLMDailyQuotaServiceTest {
    @Test
    fun `web qq and minecraft share one qq uid quota`() {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, "Asia/Shanghai")
        val now = Instant.parse("2026-08-31T08:00:00Z")

        val decisions = listOf(
            service.reserve(principal(LLMSource.WEB), "web-1", now),
            service.reserve(principal(LLMSource.QQ), "qq-1", now),
            service.reserve(principal(LLMSource.MINECRAFT), "mc-1", now),
        )

        assertTrue(decisions.all { it.status == LLMQuotaStatus.ACCEPTED })
        assertEquals(listOf(1, 2, 3), decisions.map { it.view.used })
        assertEquals(47, service.snapshot(123456L, now).view.remaining)
    }

    @Test
    fun `the fifty first request is rejected atomically`() {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, "Asia/Shanghai")
        val now = Instant.parse("2026-08-31T08:00:00Z")

        val pool = Executors.newFixedThreadPool(12)
        try {
            val decisions = pool.invokeAll(
                (1..100).map { index ->
                    Callable { service.reserve(principal(LLMSource.WEB), "request-$index", now) }
                },
            ).map { it.get() }

            assertEquals(50, decisions.count { it.status == LLMQuotaStatus.ACCEPTED })
            assertEquals(50, decisions.count { it.status == LLMQuotaStatus.EXCEEDED })
            assertEquals(50, service.snapshot(123456L, now).view.used)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same request id is idempotent within one source`() {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, "Asia/Shanghai")
        val now = Instant.parse("2026-08-31T08:00:00Z")

        val first = service.reserve(principal(LLMSource.QQ), "same-request", now)
        val duplicate = service.reserve(principal(LLMSource.QQ), "same-request", now)

        assertEquals(LLMQuotaStatus.ACCEPTED, first.status)
        assertEquals(LLMQuotaStatus.DUPLICATE, duplicate.status)
        assertEquals(1, duplicate.view.used)
    }

    @Test
    fun `quota resets at Shanghai midnight`() {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, "Asia/Shanghai")
        val beforeMidnight = Instant.parse("2026-08-31T15:59:59Z")
        val afterMidnight = Instant.parse("2026-08-31T16:00:00Z")

        val before = service.reserve(principal(LLMSource.WEB), "before", beforeMidnight)
        val after = service.reserve(principal(LLMSource.WEB), "after", afterMidnight)

        assertEquals(1, before.view.used)
        assertEquals(1, after.view.used)
        assertEquals(afterMidnight.epochSecond, before.view.resetAtEpochSeconds)
        assertTrue(after.view.resetAtEpochSeconds > before.view.resetAtEpochSeconds)
    }

    @Test
    fun `failed upstream reservation can only be refunded once`() {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, "Asia/Shanghai")
        val now = Instant.parse("2026-08-31T08:00:00Z")
        val accepted = service.reserve(principal(LLMSource.MINECRAFT), "refund", now)
        val reservation = assertNotNull(accepted.reservation)

        assertTrue(service.refund(reservation))
        assertTrue(service.refund(reservation))
        assertEquals(0, service.snapshot(123456L, now).view.used)
    }

    @Test
    fun `quota fails closed when the store is unavailable`() {
        val store = InMemoryQuotaStore().apply { available = false }
        val service = LLMDailyQuotaService(store, 50, "Asia/Shanghai")

        val decision = service.reserve(
            principal(LLMSource.WEB),
            "unavailable",
            Instant.parse("2026-08-31T08:00:00Z"),
        )

        assertEquals(LLMQuotaStatus.UNAVAILABLE, decision.status)
    }

    private fun principal(source: LLMSource) = LLMPrincipal(
        qqUid = 123456L,
        displayName = "tester",
        source = source,
        sourceIdentity = "identity-${source.value}",
    )

    private class InMemoryQuotaStore : LLMQuotaStore {
        private val counts = mutableMapOf<String, Int>()
        private val requests = mutableMapOf<String, String>()
        var available = true

        @Synchronized
        override fun reserve(
            quotaKey: String,
            requestKey: String,
            limit: Int,
            expiresAtEpochSeconds: Long,
        ): LLMQuotaStoreDecision? {
            if (!available) return null
            val current = counts[quotaKey] ?: 0
            if (requestKey in requests) {
                return LLMQuotaStoreDecision(LLMQuotaStatus.DUPLICATE, current)
            }
            if (current >= limit) {
                return LLMQuotaStoreDecision(LLMQuotaStatus.EXCEEDED, current)
            }
            requests[requestKey] = "reserved"
            counts[quotaKey] = current + 1
            return LLMQuotaStoreDecision(LLMQuotaStatus.ACCEPTED, current + 1)
        }

        @Synchronized
        override fun refund(reservation: LLMQuotaReservation): Int? {
            if (!available) return null
            val current = counts[reservation.quotaKey] ?: 0
            if (requests[reservation.requestKey] == "reserved") {
                counts[reservation.quotaKey] = (current - 1).coerceAtLeast(0)
                requests[reservation.requestKey] = "refunded"
            }
            return counts[reservation.quotaKey] ?: 0
        }

        @Synchronized
        override fun used(quotaKey: String): Int? = if (available) counts[quotaKey] ?: 0 else null
    }
}
