package org.qo.services.fallenServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.http.CacheControl
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, FallenActivityStatusController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class FallenActivityStatusControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var statusService: FallenActivityStatusService

	@MockitoBean
	lateinit var fallenTeamService: FallenTeamService

	@MockitoBean
	lateinit var nodes: Nodes

	@Test
	fun publicStatusDoesNotRequireAuthentication(): Unit = kotlinx.coroutines.runBlocking {
		Mockito.`when`(fallenTeamService.finalizedRoster()).thenReturn(emptyMap())
		Mockito.`when`(statusService.statusJson(emptyMap())).thenReturn(com.google.gson.JsonObject().apply {
			addProperty("active", true)
		})

		webTestClient.get().uri("/qo/fallen/status")
			.exchange()
			.expectStatus().isOk
			.expectHeader().cacheControl(CacheControl.noStore())
			.expectBody().jsonPath("$.active").isEqualTo(true)
	}

	@Test
	fun uploadRequiresSurvivalServerToken() {
		Mockito.`when`(nodes.getServerFromToken("wrong-token")).thenReturn(-1)

		webTestClient.post().uri("/qo/fallen/status")
			.header("token", "wrong-token")
			.bodyValue("{}")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun uploadAcceptsValidSnapshotFromSurvivalServer() {
		Mockito.`when`(nodes.getServerFromToken("server-token")).thenReturn(1)
		Mockito.`when`(statusService.update("{}")).thenReturn(true)

		webTestClient.post().uri("/qo/fallen/status")
			.header("Authorization", "Bearer server-token")
			.bodyValue("{}")
			.exchange()
			.expectStatus().isOk
			.expectBody().jsonPath("$.ok").isEqualTo(true)
	}
}
