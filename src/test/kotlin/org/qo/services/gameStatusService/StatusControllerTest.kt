package org.qo.services.gameStatusService

import com.google.gson.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.ApiApplication
import org.qo.datas.DatabaseHealth
import org.qo.datas.Nodes
import org.qo.services.loginService.IPWhitelistServices
import org.qo.services.loginService.Login
import org.qo.services.loginService.RecentLoginService
import org.qo.services.proxyRelatedServices.ProxyRelatedImpl
import org.qo.services.registrationServices.MinecraftRegistrationSessionService
import org.qo.services.registrationServices.RegistrationQuizService
import org.qo.utils.ReturnInterface
import org.qo.utils.UAUtil
import org.qo.utils.UserProcess
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class StatusControllerTest {

    private lateinit var status: Status
    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun setup() {
        status = Status()
        val mockStatus = JsonObject().apply {
            addProperty("onlinecount", 8)
            addProperty("totalcount", 100)
            addProperty("mspt", 16.2)
        }
        status.statusMap[1] = mockStatus

        val apiApplication = ApiApplication(
            Mockito.mock(UAUtil::class.java),
            ReturnInterface(),
            status,
            Mockito.mock(Login::class.java),
            Mockito.mock(IPWhitelistServices::class.java),
            Mockito.mock(ProxyRelatedImpl::class.java),
            Mockito.mock(UserProcess::class.java),
            Mockito.mock(Nodes::class.java),
            Mockito.mock(RegistrationQuizService::class.java),
            Mockito.mock(MinecraftRegistrationSessionService::class.java),
            Mockito.mock(RecentLoginService::class.java),
            Mockito.mock(DatabaseHealth::class.java),
            false
        )

        webTestClient = WebTestClient.bindToController(apiApplication).build()
    }

    @Test
    fun testRegularJsonStatusEndpoint() {
        webTestClient.get()
            .uri("/qo/download/status?id=1")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody().jsonPath("$.onlinecount").isEqualTo(8)
    }

    @Test
    fun testStatusStreamExplicitEndpoint() {
        webTestClient.get()
            .uri("/qo/download/status/stream?id=1")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectHeader().valueEquals("Cache-Control", "no-cache, no-transform")
            .expectHeader().valueEquals("X-Accel-Buffering", "no")
    }

    @Test
    fun testStreamStatusAliasEndpoint() {
        webTestClient.get()
            .uri("/qo/stream/status?id=1")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectHeader().valueEquals("Cache-Control", "no-cache, no-transform")
    }

    @Test
    fun testStatusStreamWithAcceptHeader() {
        webTestClient.get()
            .uri("/qo/download/status?id=1")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
    }
}
