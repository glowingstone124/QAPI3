package org.qo.services.llmServices

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LLMDailyQuotaServiceTest {
    @Test
    fun `QQ default is one hundred sixty units and QO upgrade retains usage within two hundred forty units`(): Unit = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(),240,160,"Asia/Shanghai",configuredPromotionMultiplier=1)
        val now = Instant.parse("2026-09-13T04:00:00Z")
        val guest = principal(LLMSource.QQ).copy(hasAccount=false)
        repeat(40) { service.reserve(guest,"guest-$it",now) }
        assertEquals(160,service.snapshot(guest.qqUid,false,now).view.limit)
        assertEquals(120,service.snapshot(guest.qqUid,false,now).view.remaining)
        val registered = service.snapshot(guest.qqUid,true,now).view
        assertEquals(240,registered.limit)
        assertEquals(40,registered.used)
        assertEquals(200,registered.remaining)
    }

    @Test
    fun `web qq and minecraft share one qq uid quota`() = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 80, "Asia/Shanghai", configuredPromotionMultiplier = 1)
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
    fun `the fifty first request is rejected atomically`() = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 80, "Asia/Shanghai", configuredPromotionMultiplier = 1)
        val now = Instant.parse("2026-08-31T08:00:00Z")

        val pool = Executors.newFixedThreadPool(12)
        try {
            val decisions = pool.invokeAll(
                (1..100).map { index ->
                    Callable { runBlocking { service.reserve(principal(LLMSource.WEB), "request-$index", now) } }
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
    fun `same request id is idempotent within one source`() = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 80, "Asia/Shanghai", configuredPromotionMultiplier = 1)
        val now = Instant.parse("2026-08-31T08:00:00Z")

        val first = service.reserve(principal(LLMSource.QQ), "same-request", now)
        val duplicate = service.reserve(principal(LLMSource.QQ), "same-request", now)

        assertEquals(LLMQuotaStatus.ACCEPTED, first.status)
        assertEquals(LLMQuotaStatus.DUPLICATE, duplicate.status)
        assertEquals(1, duplicate.view.used)
    }

    @Test
    fun `quota resets at Shanghai Monday midnight`() = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 80, "Asia/Shanghai", configuredPromotionMultiplier = 1)
        val beforeMidnight = Instant.parse("2026-09-06T15:59:59Z")
        val afterMidnight = Instant.parse("2026-09-06T16:00:00Z")

        val before = service.reserve(principal(LLMSource.WEB), "before", beforeMidnight)
        val after = service.reserve(principal(LLMSource.WEB), "after", afterMidnight)

        assertEquals(1, before.view.used)
        assertEquals(1, after.view.used)
        assertEquals(afterMidnight.epochSecond, before.view.resetAtEpochSeconds)
        assertTrue(after.view.resetAtEpochSeconds > before.view.resetAtEpochSeconds)
    }

    @Test
    fun `failed upstream reservation can only be refunded once`() = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 80, "Asia/Shanghai", configuredPromotionMultiplier = 1)
        val now = Instant.parse("2026-08-31T08:00:00Z")
        val accepted = service.reserve(principal(LLMSource.MINECRAFT), "refund", now)
        val reservation = assertNotNull(accepted.reservation)

        assertTrue(service.refund(reservation))
        assertTrue(service.refund(reservation))
        assertEquals(0, service.snapshot(123456L, now).view.used)
    }

    @Test
    fun `quota fails closed when the store is unavailable`() = runBlocking {
        val store = InMemoryQuotaStore().apply { available = false }
        val service = LLMDailyQuotaService(store, 50, 80, "Asia/Shanghai", configuredPromotionMultiplier = 1)

        val decision = service.reserve(
            principal(LLMSource.WEB),
            "unavailable",
            Instant.parse("2026-08-31T08:00:00Z"),
        )

        assertEquals(LLMQuotaStatus.UNAVAILABLE, decision.status)
    }

    @Test
    fun `guest users have 20 rounds limit while registered users have 50 rounds limit`() = runBlocking {
        val service = LLMDailyQuotaService(InMemoryQuotaStore(), 50, 20, "Asia/Shanghai", configuredPromotionMultiplier = 1)
        val now = Instant.parse("2026-08-31T08:00:00Z")
        val guestPrincipal = LLMPrincipal(999999L, "guest", LLMSource.QQ, "guest", hasAccount = false)

        for (i in 1..20) {
            val decision = service.reserve(guestPrincipal, "guest-req-$i", now)
            assertEquals(LLMQuotaStatus.ACCEPTED, decision.status)
            assertEquals(20, decision.view.limit)
            assertEquals(i, decision.view.used)
            assertEquals(20 - i, decision.view.remaining)
        }

        val exceeded = service.reserve(guestPrincipal, "guest-req-21", now)
        assertEquals(LLMQuotaStatus.EXCEEDED, exceeded.status)
        assertEquals(20, exceeded.view.limit)
        assertEquals(20, exceeded.view.used)
        assertEquals(0, exceeded.view.remaining)

        val guestSnapshot = service.snapshot(999999L, hasAccount = false, now = now)
        assertEquals(LLMQuotaStatus.EXCEEDED, guestSnapshot.status)
        assertEquals(20, guestSnapshot.view.limit)
        assertEquals(0, guestSnapshot.view.remaining)

        val userSnapshot = service.snapshot(999999L, hasAccount = true, now = now)
        assertEquals(LLMQuotaStatus.ACCEPTED, userSnapshot.status)
        assertEquals(50, userSnapshot.view.limit)
        assertEquals(30, userSnapshot.view.remaining)
    }

    @Test
    fun `promotion expiry restores base limits without resetting existing usage`(): Unit = runBlocking {
        val service = LLMDailyQuotaService(
            InMemoryQuotaStore(), 120, 80, "Asia/Shanghai",
            configuredPromotionMultiplier = 4,
            configuredPromotionEndsAt = "2026-10-01T23:59:59+08:00",
        )
        val before = Instant.parse("2026-10-01T15:59:59Z")
        val after = before.plusSeconds(1)
        val guest = principal(LLMSource.QQ).copy(hasAccount = false)
        assertEquals(480, service.effectiveLimit(true, before))
        assertEquals(320, service.effectiveLimit(false, before))
        repeat(81) { service.reserve(guest, "promotion-$it", before) }
        val expired = service.snapshot(guest.qqUid, false, after)
        assertEquals(LLMQuotaStatus.EXCEEDED, expired.status)
        assertEquals(80, expired.view.limit)
        assertEquals(81, expired.view.used)
        assertEquals(-1, expired.view.remaining)
        assertEquals(120, service.effectiveLimit(true, after))
    }

    @Test
    fun `group quota priority reserves from group pool first then falls back to personal then paid`(): Unit = runBlocking {
        val store = InMemoryQuotaStore().apply {
            balances[123456L] = 100
        }
        val service = LLMDailyQuotaService(
            store, 50, 20, "Asia/Shanghai",
            configuredPromotionMultiplier = 1,
            configuredGroupWeeklyLimit = 500,
            configuredPilotQuotaGroups = "*",
        )
        val now = Instant.parse("2026-08-31T08:00:00Z")
        val p = principal(LLMSource.QQ)
        val groupId = 946085440L

        // 1. Reserve 500 units in group -> all from group quota
        val res1 = service.reserve(p, "req-group-full", now, estimatedUnits = 500, groupId = groupId)
        assertEquals(LLMQuotaStatus.ACCEPTED, res1.status)
        assertEquals(500, res1.view.groupLimit)
        assertEquals(500, res1.view.groupUsed)
        assertEquals(0, res1.view.groupRemaining)
        assertEquals(0, res1.view.used) // personal used untouched!
        assertEquals(100, res1.view.paidCredits) // paid credits untouched!

        // 2. Next turn in group: 30 units -> group pool is 0, falls back to personal weekly quota
        val res2 = service.reserve(p, "req-fallback-personal", now, estimatedUnits = 30, groupId = groupId)
        assertEquals(LLMQuotaStatus.ACCEPTED, res2.status)
        assertEquals(30, res2.view.used) // 30 personal units used
        assertEquals(20, res2.view.remaining)
        assertEquals(100, res2.view.paidCredits) // paid credits still untouched!

        // 3. Next turn: 40 units -> 20 from personal (exhausting personal free), 20 from paid credits
        val res3 = service.reserve(p, "req-fallback-paid", now, estimatedUnits = 40, groupId = groupId)
        assertEquals(LLMQuotaStatus.ACCEPTED, res3.status)
        assertEquals(50, res3.view.used) // personal limit reached (50)
        assertEquals(0, res3.view.remaining)
        assertEquals(80, res3.view.paidCredits) // 100 - 20 = 80

        // 4. Next turn: 90 units -> group 0, personal 0, paid remaining 80 < 90 -> EXCEEDED!
        val res4 = service.reserve(p, "req-exceeded", now, estimatedUnits = 90, groupId = groupId)
        assertEquals(LLMQuotaStatus.EXCEEDED, res4.status)
        assertEquals(80, res4.view.paidCredits)
    }

    @Test
    fun `pilot groups filtering allows specified group ids and rejects non-pilot groups`(): Unit = runBlocking {
        val store = InMemoryQuotaStore()
        val service = LLMDailyQuotaService(
            store, 50, 20, "Asia/Shanghai",
            configuredPromotionMultiplier = 1,
            configuredGroupWeeklyLimit = 500,
            configuredPilotQuotaGroups = "11111, 22222",
        )
        val now = Instant.parse("2026-08-31T08:00:00Z")
        val p = principal(LLMSource.QQ)

        val defaultService = LLMDailyQuotaService(store, 50, 20, "Asia/Shanghai", configuredPromotionMultiplier = 1)
        kotlin.test.assertFalse(defaultService.isGroupQuotaEnabled(11111L))

        assertTrue(service.isGroupQuotaEnabled(11111L))
        assertTrue(service.isGroupQuotaEnabled(22222L))
        kotlin.test.assertFalse(service.isGroupQuotaEnabled(33333L))
        kotlin.test.assertFalse(service.isGroupQuotaEnabled(null))
        kotlin.test.assertFalse(service.isGroupQuotaEnabled(-1L))

        // Pilot group 11111 gets group quota
        val pilotRes = service.reserve(p, "pilot-req", now, estimatedUnits = 10, groupId = 11111L)
        assertEquals(10, pilotRes.view.groupUsed)
        assertEquals(0, pilotRes.view.used)

        // Non-pilot group 33333 does not get group quota (uses personal weekly quota)
        val nonPilotRes = service.reserve(p, "non-pilot-req", now, estimatedUnits = 10, groupId = 33333L)
        kotlin.test.assertNull(nonPilotRes.view.groupLimit)
        assertEquals(10, nonPilotRes.view.used)
    }

    @Test
    fun `snapshot returns group quota details for pilot group and accepts if group quota remains`(): Unit = runBlocking {
        val store = InMemoryQuotaStore()
        val service = LLMDailyQuotaService(
            store, 50, 20, "Asia/Shanghai",
            configuredPromotionMultiplier = 1,
            configuredGroupWeeklyLimit = 500,
            configuredPilotQuotaGroups = "*",
        )
        val now = Instant.parse("2026-08-31T08:00:00Z")
        val p = principal(LLMSource.QQ)

        // Exhaust personal quota completely
        repeat(50) {
            service.reserve(p, "personal-$it", now, estimatedUnits = 1, groupId = null)
        }

        // Snapshot without group: personal quota exceeded
        val snapPersonal = service.snapshot(p.qqUid, hasAccount = true, now = now)
        assertEquals(LLMQuotaStatus.EXCEEDED, snapPersonal.status)
        assertEquals(50, snapPersonal.view.used)
        assertEquals(0, snapPersonal.view.remaining)
        kotlin.test.assertNull(snapPersonal.view.groupLimit)

        // Snapshot with pilot group: ACCEPTED because group shared quota is available!
        val snapGroup = service.snapshot(p.qqUid, hasAccount = true, now = now, groupId = 946085440L)
        assertEquals(LLMQuotaStatus.ACCEPTED, snapGroup.status)
        assertEquals(500, snapGroup.view.groupLimit)
        assertEquals(0, snapGroup.view.groupUsed)
        assertEquals(500, snapGroup.view.groupRemaining)
    }

    private fun principal(source: LLMSource) = LLMPrincipal(
        qqUid = 123456L,
        displayName = "tester",
        source = source,
        sourceIdentity = "identity-${source.value}",
    )

    private class InMemoryQuotaStore : LLMQuotaStore {
        val counts = mutableMapOf<String, Int>()
        val groupCounts = mutableMapOf<String, Int>()
        val balances = mutableMapOf<Long, Int>()
        val requests = mutableMapOf<String, String>()
        var available = true

        override suspend fun reserve(
            quotaKey: String,
            requestKey: String,
            limit: Int,
            expiresAtEpochSeconds: Long,
        ): LLMQuotaStoreDecision? = reserveUnits(
            principal = LLMPrincipal(quotaKey.substringAfterLast(':').toLong(), "", LLMSource.WEB, ""),
            quotaKey = quotaKey,
            requestKey = requestKey,
            limit = limit,
            expiresAt = expiresAtEpochSeconds,
            units = 1,
            mode = "fast",
            provider = "",
            model = "",
            estimatedCost = LLMDailyQuotaService.COST_PER_UNIT,
        )

        override suspend fun reserveUnits(
            principal: LLMPrincipal,
            quotaKey: String,
            requestKey: String,
            limit: Int,
            expiresAt: Long,
            units: Int,
            mode: String,
            provider: String,
            model: String,
            estimatedCost: java.math.BigDecimal,
            groupId: Long?,
            groupLimit: Int,
        ): LLMQuotaStoreDecision? = synchronized(this) {
            if (!available) return null
            val current = counts[quotaKey] ?: 0
            val p = quotaKey.substringAfter("llm:weekly:").substringBeforeLast(':')
            val groupKey = if (groupId != null && groupId > 0) "$groupId:$p" else null
            val currentGroup = if (groupKey != null) groupCounts[groupKey] ?: 0 else 0
            val paid = balances[principal.qqUid] ?: 0

            if (requestKey in requests) {
                return LLMQuotaStoreDecision(
                    LLMQuotaStatus.DUPLICATE,
                    current,
                    paid,
                    groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) currentGroup else null,
                    groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
                )
            }

            val (groupUnits, weeklyUnits, paidUnits) = if (groupId != null && groupId > 0 && groupLimit > 0) {
                val availableGroup = (groupLimit - currentGroup).coerceAtLeast(0)
                val gUnits = minOf(units, availableGroup)
                val remAfterGroup = units - gUnits
                val wUnits = minOf(remAfterGroup, (limit - current).coerceAtLeast(0))
                val pUnits = remAfterGroup - wUnits
                Triple(gUnits, wUnits, pUnits)
            } else {
                val wUnits = minOf(units, (limit - current).coerceAtLeast(0))
                val pUnits = units - wUnits
                Triple(0, wUnits, pUnits)
            }

            if (paid < paidUnits) {
                return LLMQuotaStoreDecision(
                    LLMQuotaStatus.EXCEEDED,
                    current,
                    paid,
                    groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) currentGroup else null,
                    groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
                )
            }

            requests[requestKey] = "reserved"
            if (groupKey != null && groupUnits > 0) {
                groupCounts[groupKey] = currentGroup + groupUnits
            }
            if (weeklyUnits > 0) {
                counts[quotaKey] = current + weeklyUnits
            }
            if (paidUnits > 0) {
                balances[principal.qqUid] = paid - paidUnits
            }
            return LLMQuotaStoreDecision(
                LLMQuotaStatus.ACCEPTED,
                used = current + weeklyUnits,
                paidCredits = paid - paidUnits,
                groupUsed = if (groupId != null && groupId > 0 && groupLimit > 0) currentGroup + groupUnits else null,
                groupLimit = if (groupId != null && groupId > 0 && groupLimit > 0) groupLimit else null,
            )
        }

        override suspend fun refund(reservation: LLMQuotaReservation): Int? = synchronized(this) {
            if (!available) return null
            val current = counts[reservation.quotaKey] ?: 0
            if (requests[reservation.requestKey] == "reserved") {
                counts[reservation.quotaKey] = (current - 1).coerceAtLeast(0)
                requests[reservation.requestKey] = "refunded"
            }
            return counts[reservation.quotaKey] ?: 0
        }

        override suspend fun used(quotaKey: String): Int? = if (available) counts[quotaKey] ?: 0 else null

        override suspend fun groupUsed(groupId: Long, period: String): Int? =
            if (available) groupCounts["$groupId:$period"] ?: 0 else null

        override suspend fun balance(qqUid: Long): Int = balances[qqUid] ?: 0
    }
}
