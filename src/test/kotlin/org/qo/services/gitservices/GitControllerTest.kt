package org.qo.services.gitservices

import org.junit.jupiter.api.Test
import org.qo.TestApiApplication
import org.qo.services.gitservices.Controller as GitController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(
	classes = [TestApiApplication::class, GitController::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["qapi.github.webhook-secret=test-secret"]
)
@AutoConfigureWebTestClient
class GitControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	private fun signature(body: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec("test-secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
		return "sha256=" + mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
	}

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
			.header("X-Hub-Signature-256", signature(body))
			.bodyValue(body)
			.exchange()
			.expectStatus().isNoContent
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
			.header("X-Hub-Signature-256", signature(body))
			.bodyValue(body)
			.exchange()
			.expectStatus().isNoContent
	}

	@Test
	fun accept_rejectsUnsignedPayload() {
		webTestClient.post().uri("/hooks/accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
			.exchange().expectStatus().isUnauthorized
	}
}
