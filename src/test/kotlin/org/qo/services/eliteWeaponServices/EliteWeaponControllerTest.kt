package org.qo.services.eliteWeaponServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.qo.utils.ReturnInterface
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, EliteWeaponController::class, ReturnInterface::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class EliteWeaponControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var impl: EliteWeaponImpl

	@Test
	fun download_returnsPayload() {
		Mockito.`when`(impl.getEliteWeaponsFromUsername("neo")).thenReturn("[]")

		webTestClient.get()
			.uri("/qo/elite/download?username=neo")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.json("[]")
	}

	@Test
	fun create_returnsUuidOnSuccess() {
		Mockito.`when`(
			impl.handleEliteWeaponRequest("owner", "type", "desc", "name")
		).thenReturn("uuid-1")

		webTestClient.get()
			.uri("/qo/elite/create?owner=owner&type=type&description=desc&name=name")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.result").isEqualTo(true)
			.jsonPath("$.uuid").isEqualTo("uuid-1")
	}

	@Test
	fun create_returnsFalseWhenRejected() {
		Mockito.`when`(
			impl.handleEliteWeaponRequest("owner", "type", "desc", "name")
		).thenReturn(null)

		webTestClient.get()
			.uri("/qo/elite/create?owner=owner&type=type&description=desc&name=name")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.result").isEqualTo(false)
	}

	@Test
	fun add_dmgDelegatesToService() {
		Mockito.`when`(impl.addEliteWeaponDMG("uuid", "req", 3)).thenReturn("ok")

		webTestClient.post()
			.uri("/qo/elite/add?type=dmg&requester=req&uuid=uuid&amount=3")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.isEqualTo("ok")
	}

	@Test
	fun add_invalidTypeReturnsError() {
		webTestClient.post()
			.uri("/qo/elite/add?type=unknown&requester=req&uuid=uuid&amount=3")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.isEqualTo("Could not process request: type error")

		Mockito.verify(impl, Mockito.never()).addEliteWeaponDMG(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt())
		Mockito.verify(impl, Mockito.never()).addEliteWeaponKill(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt())
	}
}
