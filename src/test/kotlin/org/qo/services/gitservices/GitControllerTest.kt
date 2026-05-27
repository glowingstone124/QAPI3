package org.qo.services.gitservices

import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.services.gitservices.Controller as GitController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, GitController::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class GitControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@Test
	fun accept_handlesPushPayload() {
		val body = """
			{
				"repository": {"name": "repo"},
				"sender": {"login": "octo"},
				"commits": [
					{
						"message": "init",
						"author": {"username": "octo"}
					}
				]
			}
		""".trimIndent()

		webTestClient.post()
			.uri("/hooks/accept")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isOk
	}

	@Test
	fun accept_handlesWorkflowPayload() {
		val body = """
			{
				"action": "completed",
				"workflow_run": {
					"run_number": 1,
					"display_title": "CI",
					"status": "completed",
					"repository": {"name": "repo"}
				}
			}
		""".trimIndent()

		webTestClient.post()
			.uri("/hooks/accept")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isOk
	}
}
