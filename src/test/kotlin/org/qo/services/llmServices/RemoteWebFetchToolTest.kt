package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.qo.services.llmServices.tools.RemoteWebFetchTool
import org.qo.services.llmServices.tools.SearXNGWebSearchTool
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteWebFetchToolTest {
	@Test
	fun `passes a searched URL to the microservice and bounds the content`() = runBlocking {
		val client = HttpClient(MockEngine { request ->
			assertEquals("/fetch", request.url.encodedPath)
			assertEquals("https://example.org/a", JsonParser.parseString((request.body as TextContent).text).asJsonObject.get("url").asString)
			respond("""{"url":"https://example.org/a","title":"Article","date":"2026-09-18","content":"${"x".repeat(12_100)}","truncated":false}""")
		})
		try {
			val result = JsonParser.parseString(RemoteWebFetchTool.fetch(client, "http://10.10.0.3:9124/fetch", "https://example.org/a", exposeUrl = false)).asJsonObject
			assertEquals("Article", result.get("title").asString)
			assertEquals(false, result.has("url"))
			assertEquals(12_000, result.get("content").asString.length)
			assertTrue(result.get("truncated").asBoolean)
		} finally {
			client.close()
		}
	}

	@Test
	fun `relays a bounded service error`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"error":"blocked_url","message":"private address"}""", HttpStatusCode.BadRequest) })
		try {
			val result = JsonParser.parseString(RemoteWebFetchTool.fetch(client, "http://10.10.0.3:9124/fetch", "https://example.org/a")).asJsonObject
			assertEquals("blocked_url", result.get("error").asString)
			assertEquals("private address", result.get("message").asString)
		} finally {
			client.close()
		}
	}

	@Test
	fun `rejects a URL that did not come from search`() = runBlocking {
		val search = SearXNGWebSearchTool()
		val fetch = RemoteWebFetchTool(search)
		try {
			val args = JsonObject().apply { addProperty("result_id", "result_missing") }
			val result = JsonParser.parseString(fetch.execute(args, LLMToolContext(null, "1", "user", source = "qq", conversationKey = "qq:1:a"))).asJsonObject
			assertEquals("result_not_searched", result.get("error").asString)
		} finally {
			fetch.close()
			search.close()
		}
	}
}
