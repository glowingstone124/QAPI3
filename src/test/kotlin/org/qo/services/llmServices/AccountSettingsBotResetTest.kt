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
class AccountSettingsBotResetTest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockitoBean
    lateinit var llmServices: LLMServices

    @MockitoBean
    lateinit var settings: AccountSettingsService

    private val qq = LLMPrincipal(123456, "Tester", LLMSource.QQ, "123456", hasAccount = false)

    private fun redeem(token: String = "bot-token", uid: String = "123456", body: String = """{"request_id":"reset-bot-1"}""") =
        webTestClient.post()
            .uri("/qo/asking/v1/reset-cards/bot")
            .header("Authorization", "Bearer $token")
            .header("X-QQ-UID", uid)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    @Test
    fun `bot redemption consumes one card for the QQ identity`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("bot-token")).thenReturn(true)
        Mockito.`when`(llmServices.qqPrincipal(123456L, null)).thenReturn(qq)
        Mockito.`when`(settings.use(qq, "reset-bot-1")).thenReturn(ResetUseResult("reset-bot-1", 20))

        redeem()
            .expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.request_id").isEqualTo("reset-bot-1")
            .jsonPath("$.restored_units").isEqualTo(20)
    }

    @Test
    fun `invalid bot token is rejected before any redemption`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("bot-token")).thenReturn(false)

        redeem()
            .expectStatus().isUnauthorized
            .expectBody()
            .jsonPath("$.error.message").isEqualTo("Bot token 验证失败")
        Mockito.verify(llmServices, Mockito.never()).qqPrincipal(Mockito.anyLong(), Mockito.any())
    }

    @Test
    fun `blocked users cannot redeem through the bot`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("bot-token")).thenReturn(true)
        Mockito.`when`(llmServices.qqPrincipal(123456L, null)).thenReturn(null)

        redeem()
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.error.message").isEqualTo("该用户暂时不能使用此功能")
    }

    @Test
    fun `missing card or zero usage maps conflicts to 409`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("bot-token")).thenReturn(true)
        Mockito.`when`(llmServices.qqPrincipal(123456L, null)).thenReturn(qq)
        Mockito.`when`(settings.use(qq, "reset-bot-2")).thenThrow(SettingsConflict("没有可用的 reset 卡"))

        redeem(body = """{"request_id":"reset-bot-2"}""")
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error.message").isEqualTo("没有可用的 reset 卡")
    }

    @Test
    fun `invalid request id is rejected with 400`(): Unit = runBlocking {
        Mockito.`when`(llmServices.authenticateServerToken("bot-token")).thenReturn(true)
        Mockito.`when`(llmServices.qqPrincipal(123456L, null)).thenReturn(qq)
        Mockito.`when`(settings.use(qq, "short")).thenThrow(IllegalArgumentException("请求标识格式错误"))

        redeem(body = """{"request_id":"short"}""")
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error.message").isEqualTo("请求标识格式错误")
    }
}
