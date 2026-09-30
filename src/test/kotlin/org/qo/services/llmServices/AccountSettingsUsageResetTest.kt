package org.qo.services.llmServices

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [TestApiApplication::class, AccountSettingsController::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureWebTestClient
class AccountSettingsUsageResetTest {
    @Autowired lateinit var webTestClient: WebTestClient
    @MockitoBean lateinit var llmServices: LLMServices
    @MockitoBean lateinit var settings: AccountSettingsService
    private val admin = LLMPrincipal(999, "Admin", LLMSource.WEB, "999")

    private fun reset(token: String? = "admin-token", body: String = """{"request_id":"reset-all-api"}""") =
        webTestClient.post().uri("/qo/asking/v1/usage/reset")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

    private suspend fun authorize() {
        Mockito.`when`(llmServices.authenticateWeb("admin-token")).thenReturn(admin)
        Mockito.`when`(settings.canGrant(999)).thenReturn(true)
    }

    @Test fun `admin can reset all usage with an audited no-store response`(): Unit = runBlocking {
        authorize()
        Mockito.`when`(settings.resetAllUsage(999, "reset-all-api"))
            .thenReturn(UsageResetResult("reset-all-api", "2026-09-28", 10, 2147483648L))
        reset().expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.request_id").isEqualTo("reset-all-api")
            .jsonPath("$.period").isEqualTo("2026-09-28")
            .jsonPath("$.recipients").isEqualTo(10)
            .jsonPath("$.restored_units").isEqualTo(2147483648L)
    }

    @Test fun `missing or expired login cannot reset usage`(): Unit = runBlocking {
        reset(token = null).expectStatus().isUnauthorized
        reset(token = "invalid").expectStatus().isUnauthorized
        Mockito.verifyNoInteractions(settings)
    }

    @Test fun `non-admin is forbidden before parsing or resetting`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateWeb("admin-token")).thenReturn(admin)
        Mockito.`when`(settings.canGrant(999)).thenReturn(false)
        reset(body = "bad-json").expectStatus().isForbidden
        Mockito.verify(settings, Mockito.never()).resetAllUsage(Mockito.anyLong(), Mockito.anyString())
    }

    @Test fun `invalid body and request identifiers return 400`(): Unit = runBlocking {
        authorize()
        for (body in listOf("bad-json", "[]", "{}", """{"request_id":123}""")) {
            reset(body = body).expectStatus().isBadRequest
        }
        Mockito.verify(settings, Mockito.never()).resetAllUsage(Mockito.anyLong(), Mockito.anyString())
        Mockito.`when`(settings.resetAllUsage(999, "bad"))
            .thenThrow(IllegalArgumentException("请求标识格式错误"))
        reset(body = """{"request_id":"bad"}""").expectStatus().isBadRequest
            .expectBody().jsonPath("$.error.message").isEqualTo("请求标识格式错误")
    }

    @Test fun `unsettled requests return 409`(): Unit = runBlocking {
        authorize()
        Mockito.`when`(settings.resetAllUsage(999, "reset-all-api"))
            .thenThrow(SettingsConflict("仍有请求执行中或等待结算，请稍后重置全部用量"))
        reset().expectStatus().isEqualTo(409).expectBody()
            .jsonPath("$.error.message").isEqualTo("仍有请求执行中或等待结算，请稍后重置全部用量")
    }

    @Test fun `grant response exposes the fixed batch expiration`(): Unit = runBlocking {
        authorize()
        val request = ResetGrantRequest("grant-api-all", 1, all = true)
        Mockito.`when`(settings.grant(999, request)).thenReturn(ResetGrantResult(request.requestId, 10, 1, 1793318400))
        webTestClient.post().uri("/qo/asking/v1/reset-cards/grant")
            .header("Authorization", "Bearer admin-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"request_id":"grant-api-all","count":1,"all":true}""")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.expires_at").isEqualTo(1793318400)
    }
}
