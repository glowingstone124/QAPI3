package org.qo.services.llmServices

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.datas.ReactiveDatabase
import org.qo.datas.R2dbcDatabaseConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApiApplication::class, ReactiveDatabase::class, R2dbcDatabaseConfiguration::class])
class LLMGroupQuotaIntegrationTest {
    @Autowired lateinit var db: ReactiveDatabase
    lateinit var store: SqlLLMQuotaStore
    lateinit var service: LLMDailyQuotaService

    private val principal = LLMPrincipal(777888L, "GroupPilotUser", LLMSource.QQ, "777888", hasAccount = true)
    private val groupId = 946085440L
    private val now = Instant.parse("2026-09-02T10:00:00Z")

    @BeforeEach
    fun setup() = runBlocking {
        store = SqlLLMQuotaStore(db)
        store.schema()
        service = LLMDailyQuotaService(
            store = store,
            configuredDailyLimit = 90,
            configuredGuestDailyLimit = 30,
            quotaZoneName = "Asia/Shanghai",
            configuredPromotionMultiplier = 1,
            configuredGroupWeeklyLimit = 500,
            configuredPilotQuotaGroups = "*",
        )
        for (table in listOf(
            "ai_group_weekly_usage", "ai_usage", "ai_credit_ledger",
            "ai_quota_reservation", "ai_weekly_usage", "ai_quota_account", "ai_free_budget"
        )) {
            db.execute("DELETE FROM $table")
        }
    }

    private fun usage(units: Int) = AiQuotaUsage(
        inputTokens = 100,
        outputTokens = 200,
        cachedTokens = 0,
        reasoningTokens = null,
        actualCostCny = BigDecimal("0.005") * BigDecimal(units),
        chargedUnits = units,
        conversationId = "group-conv-1",
    )

    @Test
    fun `group quota priority reserves from group pool first then personal then paid credits`() = runBlocking {
        val period = service.period(now).toString()
        db.execute("INSERT INTO ai_quota_account VALUES (?, 100)", listOf(principal.qqUid))

        // 1. Reserve 500 units in pilot group -> completely from group shared pool
        val dec1 = service.reserve(principal, "req-1", now, estimatedUnits = 500, estimatedCost = BigDecimal("2.5"), groupId = groupId)
        assertEquals(LLMQuotaStatus.ACCEPTED, dec1.status)
        assertEquals(500, dec1.view.groupLimit)
        assertEquals(500, dec1.view.groupUsed)
        assertEquals(0, dec1.view.groupRemaining)
        assertEquals(0, dec1.view.used)
        assertEquals(100, dec1.view.paidCredits)

        val groupUsed1 = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(500, groupUsed1)
        val weeklyUsed1 = db.one("SELECT used FROM ai_weekly_usage WHERE user_id=? AND period=?", listOf(principal.qqUid, period)) { (it.get("used") as Number).toInt() }
        assertEquals(0, weeklyUsed1)

        // 2. Next turn in group: 50 units -> group pool is 0, falls back to personal weekly quota (limit = 90)
        val dec2 = service.reserve(principal, "req-2", now, estimatedUnits = 50, estimatedCost = BigDecimal("0.25"), groupId = groupId)
        assertEquals(LLMQuotaStatus.ACCEPTED, dec2.status)
        assertEquals(50, dec2.view.used)
        assertEquals(40, dec2.view.remaining)
        assertEquals(100, dec2.view.paidCredits)

        // 3. Next turn in group: 60 units -> personal remaining is 40, so 40 from personal (exhausts 90), 20 from paid credits
        val dec3 = service.reserve(principal, "req-3", now, estimatedUnits = 60, estimatedCost = BigDecimal("0.3"), groupId = groupId)
        assertEquals(LLMQuotaStatus.ACCEPTED, dec3.status)
        assertEquals(90, dec3.view.used)
        assertEquals(0, dec3.view.remaining)
        assertEquals(80, dec3.view.paidCredits)

        // 4. Next turn: 90 units -> group 0, personal 0, paid remaining 80 < 90 -> EXCEEDED!
        val dec4 = service.reserve(principal, "req-4", now, estimatedUnits = 90, estimatedCost = BigDecimal("0.45"), groupId = groupId)
        assertEquals(LLMQuotaStatus.EXCEEDED, dec4.status)
        assertEquals(80, dec4.view.paidCredits)
    }

    @Test
    fun `settlement allocates charged units to group pool and records in ai_usage`() = runBlocking {
        val period = service.period(now).toString()
        val reservation = service.reserve(principal, "req-settle", now, estimatedUnits = 30, estimatedCost = BigDecimal("0.15"), groupId = groupId).reservation!!
        assertEquals(30, reservation.view.groupUsed)

        // Actual upstream completes with 50 units
        val settledView = service.settle(reservation, usage(50))
        assertEquals(500, settledView.groupLimit)
        assertEquals(50, settledView.groupUsed)
        assertEquals(450, settledView.groupRemaining)
        assertEquals(50, settledView.groupChargedUnits)
        assertEquals(0, settledView.used)

        val groupUsedInDb = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(50, groupUsedInDb)

        val row = db.one("SELECT group_id, group_units, charged_units, weekly_units, paid_units FROM ai_usage WHERE request_key=?", listOf(reservation.requestKey)) {
            Triple(
                (it.get("group_id") as? Number)?.toLong(),
                (it.get("group_units") as? Number)?.toInt(),
                (it.get("charged_units") as? Number)?.toInt(),
            )
        }
        assertNotNull(row)
        assertEquals(groupId, row.first)
        assertEquals(50, row.second)
        assertEquals(50, row.third)
    }

    @Test
    fun `reservation refund restores units to ai_group_weekly_usage`() = runBlocking {
        val period = service.period(now).toString()
        val res = service.reserve(principal, "req-refund", now, estimatedUnits = 40, estimatedCost = BigDecimal("0.2"), groupId = groupId).reservation!!
        val beforeRefund = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(40, beforeRefund)

        assertTrue(service.refund(res))
        val afterRefund = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(0, afterRefund)
    }

    @Test
    fun `direct deduction deducts from group first and refund restores group units`() = runBlocking {
        val period = service.period(now).toString()
        val dedResult = service.deductQuota(
            qqUid = principal.qqUid,
            amount = 25,
            referenceId = "img-test-1",
            hasAccount = true,
            now = now,
            groupId = groupId,
        )
        assertTrue(dedResult.success)
        assertEquals(25, dedResult.groupDeducted)
        assertEquals(0, dedResult.weeklyDeducted)
        assertEquals(0, dedResult.paidDeducted)

        val groupUsed = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(25, groupUsed)

        val refundOk = service.refundQuota(
            qqUid = principal.qqUid,
            weeklyAmount = 0,
            paidAmount = 0,
            referenceId = "img-test-1",
            now = now,
            groupId = groupId,
            groupAmount = 25,
        )
        assertTrue(refundOk)
        val groupUsedAfter = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(0, groupUsedAfter)
    }

    @Test
    fun `cancelZombiePending refunds group units back to group pool`() = runBlocking {
        val period = service.period(now).toString()
        val res = service.reserve(principal, "zombie-req", now, estimatedUnits = 30, estimatedCost = BigDecimal("0.15"), groupId = groupId).reservation!!
        service.retain(res, null) // sets status = 'pending'

        val future = Instant.now().epochSecond + SqlLLMQuotaStore.ZOMBIE_PENDING_TIMEOUT_SECONDS + 10
        val cancelled = store.cancelZombiePending(principal.qqUid, now = future)
        assertEquals(1, cancelled)

        val groupUsedAfter = db.one("SELECT used FROM ai_group_weekly_usage WHERE group_id=? AND period=?", listOf(groupId, period)) { (it.get("used") as Number).toInt() }
        assertEquals(0, groupUsedAfter)
    }
}
