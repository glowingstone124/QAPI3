package org.qo.services.llmServices

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RAGServiceTest {
	@TempDir
	lateinit var tempDir: Path

	@Test
	fun `buildContext loads markdown content without external models`() = runBlocking {
		Files.writeString(tempDir.resolve("rules.md"), "# 主城规则\n主城禁止破坏公共建筑。")
		val service = service()
		try {
			service.reload()
			val context = service.buildContext("主城可以破坏吗？")

			assertNotNull(context)
			assertTrue(context.contains("主城规则"))
			assertTrue(context.contains("主城禁止破坏公共建筑"))
			assertTrue(context.contains("检索：keyword"))
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `buildContext filters content by group id scope`() = runBlocking {
		val groupDir = tempDir.resolve("123456")
		Files.createDirectories(groupDir)
		Files.writeString(groupDir.resolve("group_rule.md"), "# 群专有规则\n本群专有福利为每日签到积分翻倍。")

		val service = service()
		try {
			service.reload()

			val matchedContext = service.buildContext("福利如何签到？", groupId = 123456)
			assertNotNull(matchedContext)
			assertTrue(matchedContext.contains("群专有规则"))

			val otherGroupContext = service.buildContext("福利如何签到？", groupId = 999999)
			assertNull(otherGroupContext)
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `buildContext queries mnemos endpoint when enableMnemos is true`() = runBlocking {
		val mockEngine = MockEngine { request ->
			assertEquals("/v1/search", request.url.encodedPath)
			val bodyText = (request.body as TextContent).text
			val json = JsonParser.parseString(bodyText).asJsonObject
			assertEquals("主城可以破坏吗？", json.get("query").asString)
			assertEquals(5, json.get("top_k").asInt)

			val responseBody = """
				{
					"status": "ok",
					"payload": {
						"query": "主城可以破坏吗？",
						"total": 1,
						"matches": [
							{
								"id": "rules.md#1",
								"source": "rules.md",
								"title": "主城规则",
								"content": "主城禁止破坏公共建筑。",
								"group_id": null,
								"score": 12.0
							}
						]
					}
				}
			""".trimIndent()
			respond(
				content = responseBody,
				status = HttpStatusCode.OK,
				headers = headersOf(HttpHeaders.ContentType, "application/json"),
			)
		}

		val client = HttpClient(mockEngine)
		val service = RAGService(
			config = RAGService.Config(
				enabled = true,
				knowledgeDir = tempDir,
				topK = 5,
				maxContextChars = 4000,
				chunkSize = 2000,
				keywordMinScore = 1.0,
				enableMnemos = true,
				mnemosEndpoint = "http://127.0.0.1:8089",
			),
			httpClient = client,
		)

		try {
			service.reload()
			val context = service.buildContext("主城可以破坏吗？")
			assertNotNull(context)
			assertTrue(context.contains("主城规则"))
			assertTrue(context.contains("主城禁止破坏公共建筑"))
			assertTrue(context.contains("检索：mnemos"))
			assertTrue(context.contains("相关度：12.000"))
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `buildContext passes group_id to mnemos`() = runBlocking {
		val mockEngine = MockEngine { request ->
			val bodyText = (request.body as TextContent).text
			val json = JsonParser.parseString(bodyText).asJsonObject
			assertEquals(123456L, json.get("group_id").asLong)

			val responseBody = """
				{
					"status": "ok",
					"payload": {
						"query": "群福利",
						"total": 1,
						"matches": [
							{
								"id": "123456/vip.md#1",
								"source": "123456/vip.md",
								"title": "专属福利",
								"content": "每日双倍签到积分",
								"group_id": 123456,
								"score": 10.0
							}
						]
					}
				}
			""".trimIndent()
			respond(
				content = responseBody,
				status = HttpStatusCode.OK,
				headers = headersOf(HttpHeaders.ContentType, "application/json"),
			)
		}

		val client = HttpClient(mockEngine)
		val service = RAGService(
			config = RAGService.Config(
				enabled = true,
				knowledgeDir = tempDir,
				topK = 5,
				maxContextChars = 4000,
				chunkSize = 2000,
				keywordMinScore = 1.0,
				enableMnemos = true,
				mnemosEndpoint = "http://127.0.0.1:8089",
			),
			httpClient = client,
		)

		try {
			service.reload()
			val context = service.buildContext("群福利", groupId = 123456L)
			assertNotNull(context)
			assertTrue(context.contains("专属福利"))
			assertTrue(context.contains("范围：123456"))
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `buildContext handles mnemos error gracefully without throwing`() = runBlocking {
		val mockEngine = MockEngine { _ ->
			respond(
				content = """{"status":"error","payload":{"code":"server_error","message":"internal error"}}""",
				status = HttpStatusCode.InternalServerError,
				headers = headersOf(HttpHeaders.ContentType, "application/json"),
			)
		}

		val client = HttpClient(mockEngine)
		val service = RAGService(
			config = RAGService.Config(
				enabled = true,
				knowledgeDir = tempDir,
				topK = 5,
				maxContextChars = 4000,
				chunkSize = 2000,
				keywordMinScore = 1.0,
				enableMnemos = true,
				mnemosEndpoint = "http://127.0.0.1:8089",
			),
			httpClient = client,
		)

		try {
			service.reload()
			val context = service.buildContext("主城规则")
			assertNull(context)
		} finally {
			service.shutdown()
		}
	}

	@Test
	fun `reload notifies mnemos reload endpoint when enableMnemos is true`() = runBlocking {
		var reloadCalled = false
		val mockEngine = MockEngine { request ->
			if (request.url.encodedPath == "/v1/reload") {
				reloadCalled = true
				respond(
					content = """{"status":"ok","payload":{"chunks_count":42,"elapsed_ms":2}}""",
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
			} else {
				respond(
					content = "not found",
					status = HttpStatusCode.NotFound,
				)
			}
		}

		val client = HttpClient(mockEngine)
		val service = RAGService(
			config = RAGService.Config(
				enabled = true,
				knowledgeDir = tempDir,
				topK = 5,
				maxContextChars = 4000,
				chunkSize = 2000,
				keywordMinScore = 1.0,
				enableMnemos = true,
				mnemosEndpoint = "http://127.0.0.1:8089",
			),
			httpClient = client,
		)

		try {
			service.reload()
			assertTrue(reloadCalled)
		} finally {
			service.shutdown()
		}
	}

	private fun service(
		keywordMinScore: Double = 1.0,
	): RAGService = RAGService(
		RAGService.Config(
			enabled = true,
			knowledgeDir = tempDir,
			topK = 5,
			maxContextChars = 4000,
			chunkSize = 2000,
			keywordMinScore = keywordMinScore,
		),
	)
}
