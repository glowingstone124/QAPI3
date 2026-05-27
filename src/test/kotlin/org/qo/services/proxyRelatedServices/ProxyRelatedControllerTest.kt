package org.qo.services.proxyRelatedServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, ProxyRelatedController::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class ProxyRelatedControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var proxyRelatedImpl: ProxyRelatedImpl

	@Test
	fun acceptProxyHeartbeat_updatesProxy() {
		webTestClient.post()
			.uri("/qo/proxies/accept")
			.bodyValue("token-1")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.isEqualTo("ok")

		Mockito.verify(proxyRelatedImpl).heartBeatUpdate("token-1")
	}

	@Test
	fun status_returnsSimplifiedList() {
		Mockito.`when`(proxyRelatedImpl.getSimplifiedProxies()).thenReturn(
			listOf(SimplifiedProxy("proxy-a", "http://p", ProxyStatus.ALIVE))
		)

		webTestClient.get()
			.uri("/qo/proxies/status")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$[0].name").isEqualTo("proxy-a")
			.jsonPath("$[0].stat").isEqualTo("ALIVE")
	}

	@Test
	fun queryProxyInfo_returnsNotFound() {
		Mockito.`when`(proxyRelatedImpl.getProxy("missing")).thenReturn(null)

		webTestClient.get()
			.uri("/qo/proxies/query?token=missing")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.code").isEqualTo(-1)
	}

	@Test
	fun queryProxyInfo_returnsProxyInfo() {
		Mockito.`when`(proxyRelatedImpl.getProxy("token-2")).thenReturn(
			Proxy("proxy-b", "http://p2", "token-2", ProxyStatus.ALIVE, 1L)
		)

		webTestClient.get()
			.uri("/qo/proxies/query?token=token-2")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.code").isEqualTo(0)
			.jsonPath("$.name").isEqualTo("proxy-b")
	}
}
