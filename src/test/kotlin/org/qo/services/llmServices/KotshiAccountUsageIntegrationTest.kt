package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.ReactiveDatabase
import org.qo.datas.R2dbcDatabaseConfiguration
import org.qo.services.loginService.Login
import org.qo.services.loginService.KotshiPrivacyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import kotlin.test.*

@SpringBootTest(classes=[TestApiApplication::class,ReactiveDatabase::class,R2dbcDatabaseConfiguration::class])
class KotshiAccountUsageIntegrationTest {
    @Autowired lateinit var db: ReactiveDatabase
    private val uid = 987650001L
    private val otherUid = 987650002L
    private val now = Instant.parse("2026-09-13T04:00:00Z")
    private lateinit var service: KotshiAccountService

    @BeforeEach fun setup(): Unit = runBlocking {
        val schema = LLMAccessRecordSchema(db)
        schema.ensure()
        cleanup()
        service = KotshiAccountService(Mockito.mock(Login::class.java),Mockito.mock(KotshiPrivacyService::class.java),
            Mockito.mock(LLMDailyQuotaService::class.java),db,schema)
    }

    @AfterEach fun cleanup(): Unit = runBlocking {
        db.execute("DELETE FROM llm_access_records WHERE uid IN (?,?)",listOf(uid,otherUid))
    }

    private suspend fun record(source: String, status: String, tokens: Int, time: Instant = now, owner: Long = uid) {
        db.execute("""INSERT INTO llm_access_records (uid,username,source,request_id,model,stream,status,total_tokens,created_at)
            VALUES (?,?,?,?,?,false,?,?,?)""",listOf(owner,"test-user",source,"test-$owner-$source-${time.toEpochMilli()}",
            "private-provider-model-name",status,tokens,time.toEpochMilli()))
    }

    @Test fun `usage includes every channel and recent history but only the current identity`(): Unit = runBlocking {
        record("web","completed",15)
        record("qq","failed",21,now.plusMillis(1))
        record("minecraft","completed",34,now.plusMillis(2))
        record("future-channel","started",0,now.plusMillis(3))
        record("qq","completed",50,Instant.parse("2026-09-12T15:59:59Z"))
        record("web","completed",999,now,otherUid)
        val (summary,recent) = service.loadUsage(uid,now)
        assertEquals(4L,summary.requests)
        assertEquals(2L,summary.completed)
        assertEquals(1L,summary.failed)
        assertEquals(70L,summary.totalTokens)
        assertEquals(listOf("future-channel","minecraft","qq","web","qq"),recent.map { it.source })
        assertEquals(50L,recent.last().totalTokens)
        val json = JsonParser.parseString(KotshiAccountSnapshot(true,LLMQuotaView(120,40,80,1800000000,800),summary,recent).toJson()).asJsonObject
        val serialized = json.getAsJsonArray("recent_usage")
        assertEquals(5,serialized.size())
        assertTrue(serialized.all { !it.asJsonObject.has("model") && !it.asJsonObject.has("mode") })
        assertFalse(json.toString().contains("private-provider-model-name"))
        assertEquals(80,json.getAsJsonObject("quota").get("remaining").asInt)
        assertEquals(800,json.getAsJsonObject("quota").get("paid_credits").asInt)
    }

    @Test fun `recent history returns only latest twenty requests across channels`(): Unit = runBlocking {
        repeat(25) { record(if(it%2==0) "qq" else "minecraft","completed",it,now.plusMillis(it.toLong())) }
        val (summary,recent) = service.loadUsage(uid,now)
        assertEquals(25L,summary.requests)
        assertEquals(20,recent.size)
        assertEquals(24L,recent.first().totalTokens)
        assertEquals(5L,recent.last().totalTokens)
    }
}
