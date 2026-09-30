package org.qo.services.llmServices

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.qo.TestApiApplication
import org.qo.datas.ReactiveDatabase
import org.qo.datas.R2dbcDatabaseConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

@SpringBootTest(classes=[TestApiApplication::class,ReactiveDatabase::class,R2dbcDatabaseConfiguration::class], properties=["qapi.database.url=jdbc:h2:mem:///reset_cards_test;MODE=MySQL;DB_CLOSE_DELAY=-1"])
class AccountSettingsIntegrationTest {
    @Autowired lateinit var db: ReactiveDatabase
    @TempDir lateinit var tempDir: Path
    lateinit var store: SqlLLMQuotaStore
    lateinit var repository: AccountSettingsRepository
    lateinit var quota: LLMDailyQuotaService
    lateinit var settings: AccountSettingsService
    private val user = LLMPrincipal(123456,"Tester",LLMSource.WEB,"test")
    @BeforeEach fun setup() { runBlocking {
        store=SqlLLMQuotaStore(db)
        repository=SqlAccountSettingsRepository(db)
        quota=LLMDailyQuotaService(store,90,30,"Asia/Shanghai",configuredPromotionMultiplier=1)
        val providersFile=tempDir.resolve("providers.json")
        Files.writeString(providersFile,"""
            {
              "defaultProvider": "test",
              "adminUids": [999],
              "providers": {
                "test": {
                  "chatCompletionsUrl": "https://test.example/chat", "responsesUrl": "unavaliable", "anthropicUrl": "unavaliable",
                  "token": "test-token",
                  "models": { "fast": { "model": "test-fast", "protocol": "chat-completions" }, "thinking": { "model": "test-thinking", "protocol": "chat-completions" } }
                }
              }
            }
        """.trimIndent())
        settings=AccountSettingsService(repository,store,quota,ReloadableLLMProvider(providersFile))
        settings.schema()
        db.execute("CREATE TABLE IF NOT EXISTS users (uid BIGINT PRIMARY KEY)")
        for (table in listOf("ai_reset_ledger","ai_reset_card_batch","ai_reset_grant","ai_reset_balance","ai_usage_reset","ai_usage","ai_quota_reservation","ai_weekly_usage","ai_quota_account","ai_free_budget","users")) db.execute("DELETE FROM $table")
        db.execute("INSERT INTO users VALUES (?)",listOf(user.qqUid))
        db.execute("INSERT INTO ai_quota_account VALUES (?,800)",listOf(user.qqUid))
    } }
    private suspend fun spend(id: String, units: Int = 20) {
        val r=assertNotNull(quota.reserve(user,id).reservation)
        quota.settle(r,AiQuotaUsage(10,20,0,0,BigDecimal("0.005")*BigDecimal(units),units))
    }
    @Test fun `reset preserves paid credits and history and retries never reset new usage`() = runBlocking {
        spend("call-one")
        settings.grant(999,ResetGrantRequest("grant-one",2,user.qqUid))
        val result=settings.use(user,"reset-one")
        assertEquals(20,result.restoredUnits)
        assertEquals(0,quota.snapshot(user.qqUid).view.used)
        assertEquals(800,store.balance(user.qqUid))
        assertEquals(1,settings.balance(user.qqUid))
        assertEquals(1,settings.history(user.qqUid,0).items.size)
        spend("call-two",5)
        assertEquals(result,settings.use(user,"reset-one"))
        assertEquals(5,quota.snapshot(user.qqUid).view.used)
        assertEquals(1,settings.balance(user.qqUid))
        val state=settings.settings(user)
        assertEquals(2,state.statistics.calls)
        assertEquals(25,state.statistics.chargedUnits)
        assertEquals(2,state.resetHistory.size)
    }
    @Test fun `grant retries and concurrent grants do not duplicate cards`() = runBlocking {
        val request=ResetGrantRequest("grant-concurrent",2,user.qqUid)
        (1..5).map { async { settings.grant(999,request) } }.awaitAll()
        assertEquals(2,settings.balance(user.qqUid))
        assertFailsWith<IllegalArgumentException> { settings.grant(999,request.copy(count=3)) }
        assertFailsWith<IllegalArgumentException> { settings.grant(user.qqUid,request) }
        assertFailsWith<IllegalArgumentException> { settings.grant(999,request.copy(requestId="grant-invalid",userId=888)) }
        assertEquals(2,settings.balance(user.qqUid))
    }
    @Test fun `all grant snapshots existing registered and guest accounts`() = runBlocking {
        db.execute("INSERT INTO users VALUES (222)")
        db.execute("INSERT INTO ai_quota_account VALUES (333,0)")
        val request=ResetGrantRequest("grant-everyone",1,all=true)
        val grant = settings.grant(999,request)
        assertEquals(3,grant.recipients)
        assertEquals(listOf(grant.expiresAt), db.all("SELECT DISTINCT expires_at FROM ai_reset_card_batch WHERE grant_id=?", listOf(request.requestId)) {
            (it.get("expires_at") as Number).toLong()
        })
        db.execute("INSERT INTO users VALUES (444)")
        assertEquals(3,settings.grant(999,request).recipients)
        for (uid in listOf(user.qqUid,222L,333L)) assertEquals(1,settings.balance(uid))
        assertEquals(0,settings.balance(444))
    }

    @Test fun `account settings repository owns aggregate and reset transactions`() = runBlocking {
        assertTrue(repository.accountTargetExists(user.qqUid))
        assertFalse(repository.accountTargetExists(888L))
        val grant = repository.grant(999, ResetGrantRequest("repository-grant", 2, user.qqUid))
        assertEquals(ResetGrantResult("repository-grant", 1, 2, grant.expiresAt), grant)
        assertNotNull(grant.expiresAt)
        assertEquals(2, repository.balance(user.qqUid))

        spend("repository-call", 5)
        val redemption = repository.redeem(
            user.qqUid,
            "repository-reset",
            quota.period(Instant.now()).toString(),
        )
        assertEquals(ResetCardRedemptionStatus.REDEEMED, redemption.status)
        assertEquals(5, redemption.restoredUnits)
        assertEquals(1, repository.history(user.qqUid, 0).items.size)
        assertEquals(2, repository.loadSettings(user.qqUid).resetHistory.size)
    }

    @Test fun `empty usage and active requests cannot consume a card while known pending usage is reconciled`() = runBlocking {
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-no-card") }
        settings.grant(999,ResetGrantRequest("grant-check",1,user.qqUid))
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-empty") }
        val r=assertNotNull(quota.reserve(user,"active-request").reservation)
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-active") }
        quota.retain(r,AiQuotaUsage(10,20,0,0,BigDecimal("0.1"),20))
        val pendingState = settings.settings(user)
        assertEquals(0,pendingState.activeRequests)
        assertEquals(1,settings.balance(user.qqUid))
        settings.use(user,"reset-pending")
        assertEquals(0,settings.balance(user.qqUid))
    }

    @Test fun `pending request without usage remains blocked until it becomes a zombie`() = runBlocking {
        settings.grant(999,ResetGrantRequest("grant-zombie",1,user.qqUid))
        spend("completed-before-zombie")
        val r=assertNotNull(quota.reserve(user,"missing-usage").reservation)
        store.retain(r,null)

        assertEquals(1,settings.settings(user).activeRequests)
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-before-zombie") }

        db.execute(
            "UPDATE ai_quota_reservation SET created_at=? WHERE request_key=?",
            listOf(
                Instant.now().epochSecond - SqlLLMQuotaStore.ZOMBIE_PENDING_TIMEOUT_SECONDS - 1,
                r.requestKey
            )
        )
        assertEquals(0,settings.settings(user).activeRequests)
        settings.use(user,"reset-after-zombie")
        assertEquals(0,settings.balance(user.qqUid))
        assertEquals("refunded",db.one(
            "SELECT status FROM ai_quota_reservation WHERE request_key=?",
            listOf(r.requestKey)
        ) { it.get("status",String::class.java) })
    }
    @Test fun `concurrent redemption consumes exactly one card`() = runBlocking {
        settings.grant(999,ResetGrantRequest("grant-race",3,user.qqUid))
        spend("race-call")
        val results=(1..5).map { async { settings.use(user,"reset-race") } }.awaitAll()
        assertTrue(results.all { it.restoredUnits==20 })
        assertEquals(2,settings.balance(user.qqUid))
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-again") }
        assertEquals(2,settings.balance(user.qqUid))
        assertTrue(settings.history(222,0).items.isEmpty())
    }

    @Test fun `new grants expire after exactly 30 days and retries retain the original expiry`(): Unit = runBlocking {
        val request = ResetGrantRequest("grant-expiring", 2, user.qqUid)
        val grant = settings.grant(999, request)
        val createdAt = db.one("SELECT created_at FROM ai_reset_grant WHERE request_id=?", listOf(request.requestId)) {
            (it.get("created_at") as Number).toLong()
        }!!
        assertEquals(createdAt + 30 * 24 * 60 * 60L, grant.expiresAt)
        assertEquals(grant.expiresAt, settings.settings(user).resetHistory.single().expiresAt)
        spend("expiry-call")
        val historyBefore = settings.history(user.qqUid, 0)
        val budgetBefore = budget()
        db.execute("UPDATE ai_reset_card_batch SET expires_at=? WHERE grant_id=?", listOf(Instant.now().epochSecond, request.requestId))
        assertEquals(0, settings.balance(user.qqUid))
        assertEquals(0, settings.settings(user).resetCards)
        assertFailsWith<SettingsConflict> { settings.use(user, "expired-redemption") }
        assertEquals(20, quota.snapshot(user.qqUid).view.used)
        assertEquals(800, store.balance(user.qqUid))
        assertEquals(historyBefore, settings.history(user.qqUid, 0))
        assertEquals(budgetBefore, budget())
        assertEquals(grant, settings.grant(999, request))
        assertEquals(0, settings.balance(user.qqUid))
    }

    @Test fun `redemption consumes the earliest valid batch before legacy cards`(): Unit = runBlocking {
        db.execute("INSERT INTO ai_reset_balance VALUES (?,2)", listOf(user.qqUid))
        settings.grant(999, ResetGrantRequest("grant-later", 1, user.qqUid))
        settings.grant(999, ResetGrantRequest("grant-sooner", 1, user.qqUid))
        settings.grant(999, ResetGrantRequest("grant-expired", 1, user.qqUid))
        val now = Instant.now().epochSecond
        db.execute("UPDATE ai_reset_card_batch SET expires_at=? WHERE grant_id='grant-sooner'", listOf(now + 1000))
        db.execute("UPDATE ai_reset_card_batch SET expires_at=? WHERE grant_id='grant-expired'", listOf(now))
        assertEquals(4, settings.balance(user.qqUid))
        spend("batch-call-one")
        settings.use(user, "batch-reset-one")
        assertEquals(0, remaining("grant-sooner"))
        assertEquals(1, remaining("grant-later"))
        assertEquals(1, remaining("grant-expired"))
        spend("batch-call-two")
        settings.use(user, "batch-reset-two")
        assertEquals(0, remaining("grant-later"))
        assertEquals(2, settings.balance(user.qqUid))
        spend("legacy-call")
        settings.use(user, "legacy-reset")
        assertEquals(1, settings.balance(user.qqUid))
    }

    @Test fun `legacy grant schema migrates without expiring existing cards or changing grant retries`(): Unit = runBlocking {
        db.execute("ALTER TABLE ai_reset_grant DROP COLUMN expires_at")
        db.execute("INSERT INTO ai_reset_balance VALUES (?,3)", listOf(user.qqUid))
        db.execute(
            "INSERT INTO ai_reset_grant (request_id,actor_id,target_id,card_count,recipients,status,created_at) VALUES ('legacy-grant',999,?,3,1,'complete',1)",
            listOf(user.qqUid),
        )
        val migrated = SqlAccountSettingsRepository(db)
        migrated.schema()
        migrated.schema()
        assertEquals(3, migrated.balance(user.qqUid))
        assertEquals(ResetGrantResult("legacy-grant", 1, 3), migrated.grant(999, ResetGrantRequest("legacy-grant", 3, user.qqUid)))
        val newGrant = migrated.grant(999, ResetGrantRequest("migrated-grant", 1, user.qqUid))
        assertNotNull(newGrant.expiresAt)
        assertEquals(4, migrated.balance(user.qqUid))
    }

    @Test fun `admin reset clears all current users usage and preserves cards credits history budget and old periods`(): Unit = runBlocking {
        val guest = LLMPrincipal(333, "Guest", LLMSource.QQ, "333", hasAccount = false)
        spend("global-web-call", 20)
        val r = assertNotNull(quota.reserve(guest, "global-guest-call").reservation)
        quota.settle(r, AiQuotaUsage(10, 20, 0, 0, BigDecimal("0.175"), 35))
        assertEquals(-5, quota.snapshot(guest.qqUid, false).view.remaining)
        val period = quota.period(Instant.now()).toString()
        db.execute("INSERT INTO ai_weekly_usage VALUES (?, '2000-01-03', 77)", listOf(user.qqUid))
        settings.grant(999, ResetGrantRequest("global-cards", 2, all = true))
        val stateBefore = settings.settings(user)
        val historyBefore = settings.history(user.qqUid, 0)
        val budgetBefore = budget()

        val result = settings.resetAllUsage(999, "reset-all-users")
        assertEquals(UsageResetResult("reset-all-users", period, 2, 55), result)
        for (uid in listOf(user.qqUid, guest.qqUid)) {
            assertEquals(0, quota.snapshot(uid).view.used)
            assertEquals(2, settings.balance(uid))
        }
        assertEquals(800, store.balance(user.qqUid))
        assertEquals(historyBefore, settings.history(user.qqUid, 0))
        assertEquals(stateBefore.statistics, settings.settings(user).statistics)
        assertEquals(stateBefore.quota.resetAtEpochSeconds, settings.settings(user).quota.resetAtEpochSeconds)
        assertEquals(budgetBefore, budget())
        assertEquals(77, db.one("SELECT used FROM ai_weekly_usage WHERE period='2000-01-03'") { (it.get("used") as Number).toInt() })
        assertEquals(stateBefore.resetHistory, settings.settings(user).resetHistory)
        assertEquals(2, db.one("SELECT COUNT(*) AS n FROM ai_reset_ledger WHERE reference_id='reset-all-users' AND kind='admin_reset' AND delta=0") {
            (it.get("n") as Number).toInt()
        })

        spend("global-call-after", 5)
        assertEquals(result, settings.resetAllUsage(999, "reset-all-users"))
        assertEquals(result, repository.resetAllUsage(999, "reset-all-users", "2099-01-05"))
        assertEquals(5, quota.snapshot(user.qqUid).view.used)
        assertFailsWith<IllegalArgumentException> { repository.resetAllUsage(888, "reset-all-users", period) }
    }

    @Test fun `admin reset rejects unauthorized invalid and active requests without partial changes`(): Unit = runBlocking {
        spend("reset-blocked-call")
        val guest = LLMPrincipal(333, "Guest", LLMSource.QQ, "333", hasAccount = false)
        val r = assertNotNull(quota.reserve(guest, "reset-still-active").reservation)
        assertFailsWith<IllegalArgumentException> { settings.resetAllUsage(user.qqUid, "admin-reset-denied") }
        assertFailsWith<IllegalArgumentException> { settings.resetAllUsage(999, "bad") }
        assertFailsWith<SettingsConflict> { settings.resetAllUsage(999, "admin-reset-blocked") }
        assertEquals(20, quota.snapshot(user.qqUid).view.used)
        assertEquals(1, quota.snapshot(guest.qqUid).view.used)
        assertNull(repository.completedUsageReset(999, "admin-reset-blocked"))
        assertTrue(settings.settings(user).resetHistory.isEmpty())
        quota.refund(r)
        assertEquals(20, settings.resetAllUsage(999, "admin-reset-blocked").restoredUnits)
        assertEquals(0, quota.snapshot(user.qqUid).view.used)
    }

    @Test fun `admin reset reconciles known pending usage and refunds zombies before resetting`(): Unit = runBlocking {
        val known = assertNotNull(quota.reserve(user, "reset-known-pending").reservation)
        store.retain(known, AiQuotaUsage(10, 20, 0, 0, BigDecimal("0.1"), 20))
        val guest = LLMPrincipal(333, "Guest", LLMSource.QQ, "333", hasAccount = false)
        val zombie = assertNotNull(quota.reserve(guest, "reset-zombie-pending").reservation)
        store.retain(zombie, null)
        assertFailsWith<SettingsConflict> { settings.resetAllUsage(999, "reset-pending-all") }
        db.execute("UPDATE ai_quota_reservation SET created_at=1 WHERE request_key=?", listOf(zombie.requestKey))
        assertEquals(UsageResetResult("reset-pending-all", quota.period(Instant.now()).toString(), 1, 20), settings.resetAllUsage(999, "reset-pending-all"))
        assertEquals(0, quota.snapshot(user.qqUid).view.used)
        assertEquals(0, quota.snapshot(guest.qqUid).view.used)
        assertEquals("refunded", db.one("SELECT status FROM ai_quota_reservation WHERE request_key=?", listOf(zombie.requestKey)) { it.get("status", String::class.java) })
    }

    @Test fun `concurrent global resets are idempotent and empty usage succeeds`(): Unit = runBlocking {
        assertEquals(0, settings.resetAllUsage(999, "reset-empty-all").recipients)
        spend("reset-concurrent-call")
        val results = (1..5).map { async { settings.resetAllUsage(999, "reset-concurrent-all") } }.awaitAll()
        assertEquals(1, results.distinct().size)
        assertEquals(20, results.first().restoredUnits)
        assertEquals(0, quota.snapshot(user.qqUid).view.used)
        assertTrue(settings.settings(user).resetHistory.isEmpty())
        assertEquals(1, db.one("SELECT COUNT(*) AS n FROM ai_reset_ledger WHERE kind='admin_reset'") { (it.get("n") as Number).toInt() })
        assertEquals(0, settings.balance(user.qqUid))
    }

    private suspend fun remaining(grantId: String) = db.one(
        "SELECT remaining FROM ai_reset_card_batch WHERE user_id=? AND grant_id=?", listOf(user.qqUid, grantId),
    ) { (it.get("remaining") as Number).toInt() }

    private suspend fun budget() = db.all("SELECT actual_cost,reserved_cost FROM ai_free_budget ORDER BY period") {
        (it.get("actual_cost") as BigDecimal) to (it.get("reserved_cost") as BigDecimal)
    }
}
