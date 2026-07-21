package org.qo.services.inventoryServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.datas.Nodes
import org.qo.utils.Funcs
import org.qo.utils.ReturnInterface
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, InventoryViewController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class InventoryViewControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var service: InventoryViewRequestService

	@MockitoBean
	lateinit var nodes: Nodes

	@MockitoBean
	lateinit var funcs: Funcs

	@Test
	fun queryRequiresSurvivalServerToken() {
		webTestClient.get().uri("/qo/inventory/query?secrets=secret")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun queryReturnsPendingRequestForSurvivalServer() {
		Mockito.`when`(nodes.getServerFromToken("server-token")).thenReturn(1)
		Mockito.`when`(service.status("secret")).thenReturn(
			InventoryViewRequest("secret", "Owner", "Viewer", Long.MAX_VALUE)
		)

		webTestClient.get().uri("/qo/inventory/query?secrets=secret")
			.header("Token", "server-token")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.approved").isEqualTo(1)
			.jsonPath("$.viewer").isEqualTo("Viewer")
	}

	@Test
	fun validateAcceptsLegacyKeyParameterWithFullPermission() {
		Mockito.`when`(funcs.verify("bot-secret", Funcs.Perms.FULL)).thenReturn(true)
		Mockito.`when`(service.approve("request-key")).thenReturn(
			InventoryViewRequest("request-key", "Owner", "Viewer", Long.MAX_VALUE, approved = true)
		)

		webTestClient.get().uri("/qo/inventory/validate?auth=bot-secret&key=request-key")
			.exchange()
			.expectStatus().isOk
			.expectBody().jsonPath("$.code").isEqualTo(0)
	}
}
