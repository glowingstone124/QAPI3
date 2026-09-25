package org.qo.services.llmServices

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.qo.services.llmServices.tools.SearXNGWebSearchTool
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearXNGWebSearchToolTest {
	@Test
	fun `fetch authorization stays within the conversation`() {
		val tool = SearXNGWebSearchTool()
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
		val tool = SearXNGWebSearchTool()
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
			assertEquals("/searx/search", request.url.encodedPath)
			assertEquals("test query", request.url.parameters["q"])
			assertEquals("json", request.url.parameters["format"])
			respond("""{"results":[{"title":"Example","url":"https://example.org/a","content":"Useful excerpt","publishedDate":"2026-09-18"},{"title":"Bad","url":"javascript:alert(1)","content":"ignore"}]}""")
		})
		try {
			val result = JsonParser.parseString(SearXNGWebSearchTool.search(client, "http://localhost:8080/searx/search", "test query")).asJsonObject
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
	fun `explains when JSON output is disabled`() = runBlocking {
		val client = HttpClient(MockEngine { respond("Forbidden", HttpStatusCode.Forbidden) })
		try {
			val result = JsonParser.parseString(SearXNGWebSearchTool.search(client, "http://localhost:8080/search", "query")).asJsonObject
			assertEquals("search_unavailable", result.get("error").asString)
			assertTrue(result.get("message").asString.contains("search.formats"))
		} finally {
			client.close()
		}
	}

	@Test
	fun `HTTP success with no results and blocked engines reports search failure`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"results":[],"unresponsive_engines":[["brave","Suspended: too many requests"],["duckduckgo","CAPTCHA"],["google cse","Suspended: too many requests"],["startpage","Suspended: CAPTCHA"],["test","failure at https://engine.example/private"]]}""") })
		try {
			val result = JsonParser.parseString(SearXNGWebSearchTool.search(client, "http://localhost:8080/search", "NiKo major 冠军")).asJsonObject
			assertEquals("search_unavailable", result.get("error").asString)
			assertEquals(false, result.has("results"))
			assertEquals(false, result.toString().contains("engine.example"))
			assertEquals(false, result.has("query"))
		} finally {
			client.close()
		}
	}

	@Test
	fun `partial engine failure preserves useful search results`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"results":[{"title":"Example","url":"https://example.org/a","content":"Useful excerpt"}],"unresponsive_engines":[["duckduckgo","CAPTCHA"]]}""") })
		try {
			val result = JsonParser.parseString(SearXNGWebSearchTool.search(client, "http://localhost:8080/search", "query")).asJsonObject
			assertEquals(false, result.has("error"))
			assertEquals(1, result.getAsJsonArray("results").size())
			assertEquals(false, result.has("unresponsive_engines"))
		} finally {
			client.close()
		}
	}

	@Test
	fun `a genuine empty search without engine failures remains a successful result`() = runBlocking {
		val client = HttpClient(MockEngine { respond("""{"results":[],"unresponsive_engines":[]}""") })
		try {
			val result = JsonParser.parseString(SearXNGWebSearchTool.search(client, "http://localhost:8080/search", "no matches")).asJsonObject
			assertEquals(false, result.has("error"))
			assertEquals(0, result.getAsJsonArray("results").size())
		} finally {
			client.close()
		}
	}

}
