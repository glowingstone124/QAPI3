package org.qo.services.messageServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.qo.utils.UAUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, MsgController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class MsgControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var ua: UAUtil

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun upload_returnsSuccessWhenValid() {
		Mockito.`when`(nodes.validate_message(Mockito.anyString())).thenReturn(true)

		webTestClient.post()
			.uri("/qo/msglist/upload")
			.bodyValue("{}")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.isEqualTo("success")
	}

	@Test
	fun download_returnsMessagePayload() {
		webTestClient.get()
			.uri("/qo/msglist/download")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.messages").exists()
			.jsonPath("$.empty").exists()
	}
}
