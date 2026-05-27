package org.qo.services.metroServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, MetroServiceController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class MetroServiceControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var serviceImpl: MetroServiceImpl

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun downloadMetro_returnsJson() {
		Mockito.`when`(serviceImpl.getMetroJson()).thenReturn("{\"lines\":[]}")

		webTestClient.get()
			.uri("/qo/metro/download")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.lines").isArray
	}

	@Test
	fun uploadMetro_returnsServiceResult() {
		Mockito.`when`(serviceImpl.preInsertCheck(Mockito.anyString(), Mockito.anyString()))
			.thenReturn("OK")

		webTestClient.post()
			.uri("/qo/metro/upload")
			.header("Token", "test-token")
			.bodyValue("{}")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.isEqualTo("OK")
	}
}
