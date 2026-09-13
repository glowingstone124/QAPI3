package org.qo.services.llmServices

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [TestApiApplication::class, LLMController::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureWebTestClient
class LLMControllerQuotaTest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockitoBean
    lateinit var llmServices: LLMServices

    @MockitoBean
    lateinit var kotshiConversationService: KotshiConversationService

    @MockitoBean
    lateinit var tokenStatisticsService: LLMTokenStatisticsService

    private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

    @Test
    fun `stream quota rejection uses HTTP 429 before SSE starts`(): Unit = runBlocking {
        val quota = LLMQuotaView(50, 50, 0, 1_800_000_000L)
        Mockito.`when`(llmServices.modelPresetFromRequest("fast")).thenReturn("fast")
        Mockito.`when`(
            llmServices.streamChat(anyString(), eq("login-token"), eq("fast"), Mockito.isNull(), Mockito.isNull()),
        )
            .thenReturn(
                LLMStreamResult(
                    429,
                    flowOf("""{"error":{"code":"weekly_quota_exceeded"}}"""),
                    quota,
                ),
            )

        webTestClient.post()
            .uri("/qo/asking/v1/chat/completions?model=fast")
            .header("Authorization", "Bearer login-token")
            .header("Origin", "http://localhost:8080")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"stream":true,"messages":[{"role":"user","content":"hello"}]}""")
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectHeader().valueEquals("X-RateLimit-Limit", "50")
            .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
            .expectHeader().valueEquals("X-RateLimit-Reset", "1800000000")
            .expectHeader().exists("Retry-After")
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("weekly_quota_exceeded")
    }

    @Test
    fun `stream endpoint rejects an untrusted web origin`(): Unit = runBlocking {
        webTestClient.post()
            .uri("/qo/asking/v1/chat/completions?model=fast")
            .header("Authorization", "Bearer login-token")
            .header("Origin", "https://untrusted.example")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"stream":true,"messages":[{"role":"user","content":"hello"}]}""")
            .exchange()
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("origin_not_allowed")
    }

    @Test
    fun `quota endpoint exposes shared account usage`(): Unit = runBlocking {
        val quota = LLMQuotaView(50, 17, 33, 1_800_000_000L)
        Mockito.`when`(llmServices.quotaStatus("login-token")).thenReturn(
            LLMNonStreamResult(
                200,
                """{"limit":50,"used":17,"remaining":33,"reset_at":1800000000}""",
                quota,
            ),
        )

        webTestClient.get()
            .uri("/qo/asking/v1/quota")
            .header("Authorization", "Bearer login-token")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-RateLimit-Remaining", "33")
            .expectBody()
            .jsonPath("$.used").isEqualTo(17)
            .jsonPath("$.remaining").isEqualTo(33)
    }

    @Test
    fun `stream endpoint returns ServerSentEvent flow when successful`(): Unit = runBlocking {
        val quota = LLMQuotaView(50, 10, 40, 1_800_000_000L)
        Mockito.`when`(llmServices.modelPresetFromRequest("fast")).thenReturn("fast")
        Mockito.`when`(
            llmServices.streamChat(anyString(), eq("login-token"), eq("fast"), Mockito.isNull(), Mockito.isNull()),
        )
            .thenReturn(
                LLMStreamResult(
                    200,
                    flowOf("""{"id":"1","choices":[{"delta":{"content":"Hi"}}]}"""),
                    quota,
                ),
            )

        webTestClient.post()
            .uri("/qo/asking/v1/chat/completions?model=fast")
            .header("Authorization", "Bearer login-token")
            .header("Origin", "http://localhost:8080")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"stream":true,"messages":[{"role":"user","content":"hello"}]}""")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectHeader().valueEquals("X-RateLimit-Limit", "50")
            .expectHeader().valueEquals("X-RateLimit-Remaining", "40")
    }

    @Test
    fun `botChatCompletions forwards X-QQ-Group-Name to llmServices`(): Unit = runBlocking {
        Mockito.`when`(
            llmServices.completeBotChat(
                body = Mockito.anyString(),
                token = eq("server-token"),
                qqUid = eq(12345L),
                qqGroupId = eq(67890L),
                qqName = Mockito.isNull(),
                qqMessageId = Mockito.isNull(),
                model = eq("fast"),
                clientRequestId = Mockito.isNull(),
                qqGroupName = eq("QuantumGroup"),
            )
        ).thenReturn(LLMNonStreamResult(200, """{"response":"ok"}"""))

        webTestClient.post()
            .uri("/qo/asking/v1/chat/completions/bot?model=fast")
            .header("token", "server-token")
            .header("X-QQ-UID", "12345")
            .header("X-QQ-Group-ID", "67890")
            .header("X-QQ-Group-Name", "QuantumGroup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"messages":[{"role":"user","content":"hi"}]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.response").isEqualTo("ok")
    }

    @Test
    fun `group token stats endpoint returns group token statistics`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("server-token")).thenReturn(true)
        Mockito.`when`(tokenStatisticsService.listGroupStats(100)).thenReturn(
            listOf(
                LLMGroupTokenStats(
                    groupName = "test_group",
                    cachedTokens = 200,
                    uncachedTokens = 300,
                    completionTokens = 100,
                    totalTokens = 600,
                    requestCount = 2,
                    createdAt = 1000L,
                    updatedAt = 2000L,
                )
            )
        )

        webTestClient.get()
            .uri("/qo/asking/v1/stats/tokens/groups")
            .header("token", "server-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].group_name").isEqualTo("test_group")
            .jsonPath("$[0].cached_tokens").isEqualTo(200)
            .jsonPath("$[0].uncached_tokens").isEqualTo(300)
            .jsonPath("$[0].total_tokens").isEqualTo(600)
            .jsonPath("$[0].request_count").isEqualTo(2)
    }

    @Test
    fun `get specific group token stats returns group stats`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("server-token")).thenReturn(true)
        Mockito.`when`(tokenStatisticsService.getGroupStats("test_group")).thenReturn(
            LLMGroupTokenStats(
                groupName = "test_group",
                cachedTokens = 500,
                uncachedTokens = 200,
                completionTokens = 50,
                totalTokens = 750,
                requestCount = 5,
                createdAt = 1000L,
                updatedAt = 3000L,
            )
        )

        webTestClient.get()
            .uri("/qo/asking/v1/stats/tokens/groups/test_group")
            .header("token", "server-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.group_name").isEqualTo("test_group")
            .jsonPath("$.cached_tokens").isEqualTo(500)
            .jsonPath("$.uncached_tokens").isEqualTo(200)
            .jsonPath("$.total_tokens").isEqualTo(750)
    }

    @Test
    fun `user token stats endpoint returns user token statistics`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("server-token")).thenReturn(true)
        Mockito.`when`(tokenStatisticsService.getUserStats(99999L)).thenReturn(
            LLMUserTokenStats(
                qqUid = 99999L,
                cachedTokens = 1000,
                uncachedTokens = 500,
                completionTokens = 200,
                totalTokens = 1700,
                requestCount = 10,
                createdAt = 1000L,
                updatedAt = 4000L,
            )
        )

        webTestClient.get()
            .uri("/qo/asking/v1/stats/tokens/users/99999")
            .header("token", "server-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.qq_uid").isEqualTo(99999)
            .jsonPath("$.cached_tokens").isEqualTo(1000)
            .jsonPath("$.uncached_tokens").isEqualTo(500)
            .jsonPath("$.total_tokens").isEqualTo(1700)
            .jsonPath("$.request_count").isEqualTo(10)
    }
}
