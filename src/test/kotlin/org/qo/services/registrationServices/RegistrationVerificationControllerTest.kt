package org.qo.services.registrationServices

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
import org.springframework.http.MediaType

@SpringBootTest(
	classes = [TestApiApplication::class, RegistrationVerificationController::class, RegistrationQuizService::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class RegistrationVerificationControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun methodsExposeLegacyQuizAndReservedMinecraftOption() {
		webTestClient.get().uri("/qo/registration/verification-methods")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.defaultMethod").isEqualTo("quiz")
			.jsonPath("$.methods[0].id").isEqualTo("quiz")
			.jsonPath("$.methods[0].available").isEqualTo(true)
			.jsonPath("$.methods[0].questionCount").isEqualTo(10)
			.jsonPath("$.methods[0].passingScore").isEqualTo(6)
			.jsonPath("$.methods[1].id").isEqualTo("minecraft")
			.jsonPath("$.methods[1].available").isEqualTo(false)
			.jsonPath("$.methods[1].state").isEqualTo("reserved")
	}

	@Test
	fun minecraftSessionEndpointIsReserved() {
		webTestClient.post().uri("/qo/registration/minecraft/session")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""{"name":"Alex_123","uid":123456}""")
			.exchange()
			.expectStatus().isEqualTo(501)
			.expectBody()
			.jsonPath("$.code").isEqualTo("minecraft_verification_reserved")
	}

	@Test
	fun minecraftResultRequiresSurvivalServerToken() {
		webTestClient.post().uri("/qo/registration/minecraft/result")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""{"sessionId":"session","name":"Alex_123","passed":true}""")
			.exchange()
			.expectStatus().isUnauthorized

		Mockito.`when`(nodes.getServerFromToken("server-token")).thenReturn(1)
		webTestClient.post().uri("/qo/registration/minecraft/result")
			.header("Token", "server-token")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""{"sessionId":"session","name":"Alex_123","passed":true}""")
			.exchange()
			.expectStatus().isEqualTo(501)
	}
}
