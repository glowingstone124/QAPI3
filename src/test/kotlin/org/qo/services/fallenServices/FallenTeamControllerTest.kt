package org.qo.services.fallenServices

import kotlinx.coroutines.runBlocking
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
	classes = [TestApiApplication::class, FallenTeamController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class FallenTeamControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var fallenTeamService: FallenTeamService

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun currentSelection_returnsLockedTeamForAuthenticatedUser() = runBlocking {
		val selection = FallenTeamSelection("alex", FallenTeam.B, 1234L)
		Mockito.`when`(fallenTeamService.selectionForToken("login-token"))
			.thenReturn("alex" to selection)

		webTestClient.get()
			.uri("/qo/authorization/fallen/team")
			.header("Authorization", "Bearer login-token")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.selected").isEqualTo(true)
			.jsonPath("$.team").isEqualTo("B")
			.jsonPath("$.selectedAt").isEqualTo(1234)
	}

	@Test
	fun selectTeam_rejectsASecondSelectionAndReturnsOriginalTeam() = runBlocking {
		val selection = FallenTeamSelection("alex", FallenTeam.A, 1234L)
		Mockito.`when`(fallenTeamService.select("login-token", "{\"team\":\"C\"}"))
			.thenReturn("alex" to FallenSelectionResult.AlreadySelected(selection))

		webTestClient.post()
			.uri("/qo/authorization/fallen/team")
			.header("token", "login-token")
			.bodyValue("{\"team\":\"C\"}")
			.exchange()
			.expectStatus().isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("already_selected")
			.jsonPath("$.team").isEqualTo("A")
	}

	@Test
	fun serverSelection_requiresTheSurvivalServerToken() {
		Mockito.`when`(nodes.getServerFromToken("wrong-token")).thenReturn(-1)

		webTestClient.get()
			.uri("/qo/fallen/team?username=alex")
			.header("token", "wrong-token")
			.exchange()
			.expectStatus().isUnauthorized
	}
}
