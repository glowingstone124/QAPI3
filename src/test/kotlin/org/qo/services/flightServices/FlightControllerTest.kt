package org.qo.services.flightServices

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, FlightController::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class FlightControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var service: FlightService

	@Test
	fun download_returnsServiceData() {
		Mockito.`when`(service.getAllActiveFlights()).thenReturn("[]")

		webTestClient.get()
			.uri("/qo/flight/download")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.json("[]")
	}

	@Test
	fun upload_withInvalidToken_returnsErrorPayloadAndDoesNotUpdate() {
		webTestClient.post()
			.uri("/qo/flight/upload")
			.header("auth", "invalid-token")
			.bodyValue("{}")
			.exchange()
		.expectStatus().isOk
			.expectBody(String::class.java)
			.consumeWith { result ->
				val body = result.responseBody ?: ""
				assertTrue(body.trimStart().startsWith("Error: Internal Server Error"))
			}

		Mockito.verify(service, Mockito.never()).updateRecords(Mockito.anyString())
	}
}
