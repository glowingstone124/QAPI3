package org.qo.services.llmServices

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.qo.services.llmServices.tools.DuckDuckGoWebSearchTool
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuckDuckGoWebSearchToolTest {
	@Test
	fun `spring creates the remote search tool with the fixed URL`() {
		AnnotationConfigApplicationContext().use { context ->
			context.register(DuckDuckGoWebSearchTool::class.java)
			context.refresh()
			val bean = context.getBean(DuckDuckGoWebSearchTool::class.java)
			assertEquals("web_search", bean.id)
			val endpoint = bean.javaClass.getDeclaredField("endpoint").apply { isAccessible = true }.get(bean)
			assertEquals("http://10.10.0.3:9123/search", endpoint)
		}
	}

	@Test
	fun `fetch authorization stays within the conversation`() {
		val tool = DuckDuckGoWebSearchTool()
		try {
			val first = LLMToolContext(null, "1", "user", source = "qq", conversationKey = "qq:1:a")
			val second = LLMToolContext(null, "1", "user", source = "qq", conversationKey = "qq:1:b")
			val result = JsonParser.parseString(tool.prepareResults(first, """{"query":"https://example.org/article","results":[{"title":"Example","url":"https://example.org/article","snippet":"Text"}]}""")).asJsonObject
			val item = result.getAsJsonArray("results")[0].asJsonObject
			assertEquals(false, item.has("url"))
			assertEquals(false, result.has("query"))
			assertEquals(false, result.toString().contains("example.org"))
			val id = item.get("result_id").asString
			assertEquals("https://example.org/article", tool.resolveRecentResult(first, id))
			assertEquals(null, tool.resolveRecentResult(second, id))
		} finally {
			tool.close()
		}
	}

	@Test
	fun `web search keeps source URLs`() {
		val tool = DuckDuckGoWebSearchTool()
		try {
			val context = LLMToolContext(null, "1", "user", source = "web", conversationKey = "web:1:a")
			val result = JsonParser.parseString(tool.prepareResults(context, """{"results":[{"url":"https://example.org/article"}]}""")).asJsonObject
			assertEquals("https://example.org/article", result.getAsJsonArray("results")[0].asJsonObject.get("url").asString)
			assertEquals("https://example.org/article", tool.resolveRecentResult(context, "https://example.org/article"))
		} finally {
			tool.close()
		}
	}

	@Test
	fun `queries JSON endpoint and returns bounded source links`() = runBlocking {
		val client = HttpClient(MockEngine { request ->
			assertEquals("/search", request.url.encodedPath)
			assertEquals("test query", request.url.parameters["q"])
			assertEquals(null, request.url.parameters["format"])
			respond("""{"results":[{"title":"Example","url":"https://example.org/a","snippet":"Useful excerpt"},{"title":"Bad","url":"javascript:alert(1)","snippet":"ignore"}]}""")
		})
		try {
			val result = JsonParser.parseString(DuckDuckGoWebSearchTool.search(client, "http://localhost:8080/search", "test query")).asJsonObject
			assertEquals("web_search", result.get("tool").asString)
			val results = result.getAsJsonArray("results")
			assertEquals(1, results.size())
			assertEquals("https://example.org/a", results[0].asJsonObject.get("url").asString)
			assertEquals("Useful excerpt", results[0].asJsonObject.get("snippet").asString)
		} finally {
			client.close()
		}
	}

	@Test
	fun `reports DuckDuckGo rate limits`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"error":"captcha"}""", HttpStatusCode.TooManyRequests) })
		try {
			val result = JsonParser.parseString(DuckDuckGoWebSearchTool.search(client, "http://localhost:8080/search", "query")).asJsonObject
			assertEquals("search_unavailable", result.get("error").asString)
			assertTrue(result.get("message").asString.contains("频率限制"))
		} finally {
			client.close()
		}
	}

	@Test
	fun `missing results is a service failure`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"error":"invalid_response","message":"https://engine.example/private"}""") })
		try {
			val result = JsonParser.parseString(DuckDuckGoWebSearchTool.search(client, "http://localhost:8080/search", "NiKo major 冠军")).asJsonObject
			assertEquals("search_unavailable", result.get("error").asString)
			assertEquals(false, result.has("results"))
			assertEquals(false, result.toString().contains("engine.example"))
			assertEquals(false, result.has("query"))
		} finally {
			client.close()
		}
	}

	@Test
	fun `search caps result count at eight`() = runBlocking {
		val items = (1..10).joinToString(",") { """{"title":"Result $it","url":"https://example.org/$it","snippet":"Text"}""" }
		val client = HttpClient(MockEngine { respond("""{"results":[$items]}""") })
		try {
			val result = JsonParser.parseString(DuckDuckGoWebSearchTool.search(client, "http://localhost:8080/search", "query")).asJsonObject
			assertEquals(false, result.has("error"))
			assertEquals(8, result.getAsJsonArray("results").size())
		} finally {
			client.close()
		}
	}

	@Test
	fun `a genuine empty search remains a successful result`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"results":[]}""") })
		try {
			val result = JsonParser.parseString(DuckDuckGoWebSearchTool.search(client, "http://localhost:8080/search", "no matches")).asJsonObject
			assertEquals(false, result.has("error"))
			assertEquals(0, result.getAsJsonArray("results").size())
		} finally {
			client.close()
		}
	}

}
