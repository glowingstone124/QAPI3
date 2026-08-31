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

    @Test
    fun `stream quota rejection uses HTTP 429 before SSE starts`() = runBlocking {
        val quota = LLMQuotaView(50, 50, 0, 1_800_000_000L)
        Mockito.`when`(llmServices.modelPresetFromRequest("fast")).thenReturn("fast")
        Mockito.`when`(
            llmServices.streamChat(anyString(), Mockito.eq("login-token"), Mockito.eq("fast"), Mockito.isNull(), Mockito.isNull()),
        )
            .thenReturn(
                LLMStreamResult(
                    429,
                    flowOf("""{"error":{"code":"daily_quota_exceeded"}}"""),
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
            .jsonPath("$.error.code").isEqualTo("daily_quota_exceeded")
    }

    @Test
    fun `stream endpoint rejects an untrusted web origin`() = runBlocking {
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
    fun `quota endpoint exposes shared account usage`() = runBlocking {
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
    fun `stream endpoint returns ServerSentEvent flow when successful`() = runBlocking {
        val quota = LLMQuotaView(50, 10, 40, 1_800_000_000L)
        Mockito.`when`(llmServices.modelPresetFromRequest("fast")).thenReturn("fast")
        Mockito.`when`(
            llmServices.streamChat(anyString(), Mockito.eq("login-token"), Mockito.eq("fast"), Mockito.isNull(), Mockito.isNull()),
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
}
