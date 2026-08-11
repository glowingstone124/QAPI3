package org.qo.services.registrationServices

import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [
		TestApiApplication::class,
		RegistrationVerificationController::class,
		RegistrationQuizService::class,
		MinecraftRegistrationSessionService::class,
		ReturnInterface::class
	],
	properties = ["qapi.registration.chambers-enabled=false"],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class RegistrationVerificationAvailabilityTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun `minecraft verification stays reserved by default`() {
		webTestClient.get().uri("/qo/registration/verification-methods")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.methods[1].id").isEqualTo("minecraft")
			.jsonPath("$.methods[1].available").isEqualTo(false)
			.jsonPath("$.methods[1].state").isEqualTo("reserved")
	}

	@Test
	fun `minecraft verification endpoints reject requests while reserved`() {
		val requests = listOf(
			"/qo/registration/minecraft/session" to """{"name":"Alex_123","uid":123456}""",
			"/qo/registration/minecraft/claim" to """{"name":"Alex_123"}""",
			"/qo/registration/minecraft/status" to """{"sessionId":"session","name":"Alex_123","uid":123456}""",
			"/qo/registration/minecraft/result" to """{"sessionId":"session","name":"Alex_123","passed":true}"""
		)

		requests.forEach { (uri, body) ->
			webTestClient.post().uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.exchange()
				.expectStatus().isEqualTo(503)
				.expectBody()
				.jsonPath("$.code").isEqualTo("minecraft_verification_unavailable")
		}
	}
}
