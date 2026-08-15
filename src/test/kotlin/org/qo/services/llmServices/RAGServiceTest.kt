package org.qo.services.llmServices

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RAGServiceTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `buildContext loads markdown content without blocking bridge`() = runBlocking {
		Files.writeString(tempDir.resolve("rules.md"), "# 主城规则\n主城禁止破坏公共建筑。")
		val service = service(embeddingEnabled = false)
		try {
			service.reload()
			val context = service.buildContext("主城可以破坏吗？")

			assertNotNull(context)
			assertTrue(context.contains("主城规则"))
			assertTrue(context.contains("主城禁止破坏公共建筑"))
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `reload sends embedding bearer token`() = runBlocking {
		Files.writeString(tempDir.resolve("guide.md"), "# 指南\n新玩家先阅读规则。")
		val authorizations = mutableListOf<String?>()
		val client = HttpClient(MockEngine { request ->
			authorizations += request.headers[HttpHeaders.Authorization]
			respond(
				content = """{"data":[{"embedding":[0.1,0.2,0.3]}]}""",
				status = HttpStatusCode.OK,
				headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
			)
		})
		val service = service(embeddingEnabled = true, client = client)
		try {
			service.reload()

			assertEquals<List<String?>>(listOf("Bearer test-token"), authorizations)
		} finally {
			service.shutdown()
		}
	}

	private fun service(
		embeddingEnabled: Boolean,
		client: HttpClient = HttpClient(),
	): RAGService = RAGService(
		RAGService.Config(
			enabled = true,
			knowledgeDir = tempDir,
			topK = 5,
			maxContextChars = 4000,
			chunkSize = 2000,
			embeddingMinScore = 0.1,
			keywordMinScore = 1.0,
			embeddingEnabled = embeddingEnabled,
			embeddingUrl = "https://example.com/v1/embeddings",
			embeddingModel = "test-embedding-model",
			embeddingToken = "test-token",
		),
		client,
	)
}
