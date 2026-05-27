package org.qo.services.advancementServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.Enumerations.AdvancementsEnum
import org.qo.datas.Nodes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, AdvancementServiceController::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class AdvancementServiceControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var advancementServiceImpl: AdvancementServiceImpl

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun upload_rejectsInvalidProvider() {
		Mockito.`when`(nodes.getServerFromToken("bad-token")).thenReturn(-1)

		val body = """
			{
				"player": "alex",
				"advancement": 2
			}
		""".trimIndent()

		webTestClient.post()
			.uri("/qo/advancement/upload")
			.header("Token", "bad-token")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.result").isEqualTo(false)
			.jsonPath("$.error").isEqualTo("invalid provider")
	}

	@Test
	fun upload_acceptsValidAdvancement() {
		val advancement = AdvancementsEnum.fromId(2)!!
		Mockito.`when`(nodes.getServerFromToken("ok-token")).thenReturn(1)
		Mockito.`when`(
			advancementServiceImpl.addAdvancementCompletion(
				advancement,
				"alex"
			)
		).thenReturn(AdvancementServiceImpl.AddAdvancementResult.SUCCESS)

		val body = """
			{
				"player": "alex",
				"advancement": 2
			}
		""".trimIndent()

		webTestClient.post()
			.uri("/qo/advancement/upload")
			.header("Token", "ok-token")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.result").isEqualTo(true)
			.jsonPath("$.error").isEqualTo("")
	}
}
