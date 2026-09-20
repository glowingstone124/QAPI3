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
    lateinit var quota: LLMDailyQuotaService
    lateinit var settings: AccountSettingsService
    private val user = LLMPrincipal(123456,"Tester",LLMSource.WEB,"test")
    @BeforeEach fun setup() { runBlocking {
        store=SqlLLMQuotaStore(db)
        quota=LLMDailyQuotaService(store,90,30,"Asia/Shanghai")
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
        settings=AccountSettingsService(db,store,quota,ReloadableLLMProvider(providersFile))
        settings.schema()
        db.execute("CREATE TABLE IF NOT EXISTS users (uid BIGINT PRIMARY KEY)")
        for (table in listOf("ai_reset_ledger","ai_reset_grant","ai_reset_balance","ai_usage","ai_quota_reservation","ai_weekly_usage","ai_quota_account","ai_free_budget","users")) db.execute("DELETE FROM $table")
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
        assertEquals(3,settings.grant(999,request).recipients)
        db.execute("INSERT INTO users VALUES (444)")
        assertEquals(3,settings.grant(999,request).recipients)
        for (uid in listOf(user.qqUid,222L,333L)) assertEquals(1,settings.balance(uid))
        assertEquals(0,settings.balance(444))
    }
    @Test fun `empty usage and active or pending requests cannot consume a card`() = runBlocking {
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-no-card") }
        settings.grant(999,ResetGrantRequest("grant-check",1,user.qqUid))
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-empty") }
        val r=assertNotNull(quota.reserve(user,"active-request").reservation)
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-active") }
        quota.retain(r,AiQuotaUsage(10,20,0,0,BigDecimal("0.1"),20))
        assertFailsWith<SettingsConflict> { settings.use(user,"reset-pending") }
        assertEquals(1,settings.balance(user.qqUid))
        store.reconcileKnownUsage(user.qqUid)
        settings.use(user,"reset-completed")
        assertEquals(0,settings.balance(user.qqUid))
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
}
