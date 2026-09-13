package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class LLMQuotaAdmissionTest {
    @TempDir lateinit var tempDir: Path
    private val services = Mockito.mock(LLMServices::class.java)
    private val principal = LLMPrincipal(1425411424,"QQ user",LLMSource.QQ,"1425411424",false)
    private val request = LLMServices.NormalizedRequest("fast","chat-model",
        """{"max_tokens":2048,"messages":[{"role":"user","content":"hello"}]}""",
        JsonObject(),"hello",false,LLMReasoningEffort.NONE)

    private fun provider(priced: Boolean): LLMProvider {
        val root = JsonParser.parseString("""{"defaultProvider":"chat","providers":{"chat":{
            "chatCompletionsUrl":"https://example.com/chat","responsesUrl":"unavaliable","anthropicUrl":"unavaliable","token":"test-token",
            "models":{"fast":{"model":"chat-model","protocol":"chat-completions"},"thinking":{"model":"chat-model","protocol":"chat-completions"}}
        }}}""").asJsonObject
        if (priced) root.getAsJsonObject("providers").getAsJsonObject("chat").getAsJsonObject("models").getAsJsonObject("fast")
            .add("pricing",JsonParser.parseString("""{"inputCnyPerMillion":1,"outputCnyPerMillion":4,"cachedInputCnyPerMillion":0.02}"""))
        val file = tempDir.resolve("providers.json")
        Files.writeString(file,root.toString())
        return LLMProvider.fromConfig(file)
    }

    private fun quota(failure: Exception? = null): LLMDailyQuotaService {
        val quota = LLMDailyQuotaService(object : LLMQuotaStore {
            override suspend fun reserve(quotaKey: String, requestKey: String, limit: Int, expiresAtEpochSeconds: Long): LLMQuotaStoreDecision {
                if (failure != null) throw failure
                assertEquals(80,limit)
                return LLMQuotaStoreDecision(LLMQuotaStatus.ACCEPTED,2)
            }
            override suspend fun refund(reservation: LLMQuotaReservation): Int = 0
            override suspend fun used(quotaKey: String): Int = 0
        },120,80,"Asia/Shanghai")
        Mockito.`when`(services.dailyQuotaService).thenReturn(quota)
        return quota
    }

    @Test fun `missing fast pricing is a configuration error rather than quota outage`(): Unit = runBlocking {
        quota()
        val decision = services.reserveQuota(principal,"qq-message",request,provider(false))
        assertEquals(LLMQuotaStatus.PRICING_UNAVAILABLE,decision.status)
        val failure = assertNotNull(services.quotaFailure(decision,principal))
        assertEquals("pricing_unavailable",JsonParser.parseString(failure.body).asJsonObject.getAsJsonObject("error").get("code").asString)
    }

    @Test fun `configured fast pricing admits unregistered QQ identities`(): Unit = runBlocking {
        quota()
        val decision = services.reserveQuota(principal,"qq-message",request,provider(true))
        assertEquals(LLMQuotaStatus.ACCEPTED,decision.status)
        assertEquals(80,decision.view.limit)
        assertNotNull(decision.reservation)
    }

    @Test fun `database admission errors remain unavailable rather than bypassing quota`(): Unit = runBlocking {
        quota(io.r2dbc.spi.R2dbcBadGrammarException("test failure","42000",1064))
        val decision = services.reserveQuota(principal,"qq-message",request,provider(true))
        assertEquals(LLMQuotaStatus.UNAVAILABLE,decision.status)
        assertNull(decision.reservation)
    }

    @Test fun `cancelled admission propagates cancellation`(): Unit = runBlocking {
        quota(CancellationException("cancelled"))
        assertFailsWith<CancellationException> { services.reserveQuota(principal,"qq-message",request,provider(true)) }
    }
}
