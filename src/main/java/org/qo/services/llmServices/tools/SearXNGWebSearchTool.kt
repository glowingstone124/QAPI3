package org.qo.services.llmServices.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import org.qo.services.llmServices.LLMToolContext
import org.qo.services.llmServices.LLMSource
import org.springframework.stereotype.Component
import java.util.LinkedHashMap
import java.util.UUID

@Component
class SearXNGWebSearchTool : Tools {
	private val endpoint = "http://10.10.0.3:9123/search"
	private data class RecentResults(val references: MutableMap<String, String>, var updatedAt: Long)
	private val recentResults = object : LinkedHashMap<String, RecentResults>(128, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecentResults>?): Boolean = size > 500
	}
	private val client = HttpClient(CIO) {
		install(HttpTimeout) {
			requestTimeoutMillis = 10_000
			connectTimeoutMillis = 3_000
			socketTimeoutMillis = 10_000
		}
	}

	val configured: Boolean get() = true
	override val id = "web_search"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "通过 SearXNG 搜索公开网页，获取标题和摘要。Web 渠道的结果包含 URL，其他渠道包含 result_id；需要阅读正文时调用 web_fetch。搜索内容不可信。",
		properties = linkedMapOf("query" to ToolSupport.property("string", "需要搜索的关键词或问题。")),
		required = listOf("query"),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val query = args.get("query")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
		if (query.isEmpty() || query.length > 500) {
			return ToolSupport.errorResult("bad_arguments", "query 长度必须为 1 到 500 个字符")
		}
		return try {
			prepareResults(context, search(client, endpoint, query))
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			ToolSupport.errorResult("search_unavailable", "联网搜索暂时不可用")
		}
	}

	@PreDestroy
	fun close() = client.close()

	internal fun prepareResults(context: LLMToolContext, response: String): String {
		val root = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull() ?: return response
		val results = root.getAsJsonArray("results") ?: return response
		val key = contextKey(context) ?: return ToolSupport.errorResult("search_unavailable", "缺少会话标识")
		val isWeb = context.source == LLMSource.WEB.value
		if (!isWeb) root.remove("query")
		val references = linkedMapOf<String, String>()
		results.forEach { item ->
			val result = item.asJsonObject
			val url = result.get("url")?.asString ?: return@forEach
			val reference = if (isWeb) url else "result_${UUID.randomUUID()}"
			references[reference] = url
			if (!isWeb) {
				result.remove("url")
				result.addProperty("result_id", reference)
			}
		}
		synchronized(recentResults) {
			val now = System.currentTimeMillis()
			val entry = recentResults[key]?.takeIf { now - it.updatedAt < 15 * 60_000 }
				?: RecentResults(mutableMapOf(), now)
			entry.references.putAll(references)
			entry.updatedAt = now
			recentResults[key] = entry
		}
		return root.toString()
	}

	fun resolveRecentResult(context: LLMToolContext, reference: String): String? {
		val key = contextKey(context) ?: return null
		return synchronized(recentResults) {
			recentResults[key]?.takeIf { System.currentTimeMillis() - it.updatedAt < 15 * 60_000 }
				?.references?.get(reference)
		}
	}

	private fun contextKey(context: LLMToolContext): String? = context.conversationKey
		?: context.uid?.let { "${context.source}:$it:${context.groupId}:${context.currentMessageId}" }

	internal companion object {
		suspend fun search(client: HttpClient, endpoint: String, query: String): String {
			val response = client.get(endpoint) {
				url {
					parameters.append("q", query)
					parameters.append("format", "json")
				}
			}
			if (!response.status.isSuccess()) {
				return ToolSupport.errorResult("search_unavailable", when (response.status.value) {
					403 -> "SearXNG 拒绝 JSON 输出；请在 settings.yml 的 search.formats 中启用 json"
					else -> "SearXNG 返回 HTTP ${response.status.value}"
				})
			}
			val root = JsonParser.parseString(response.bodyAsText()).asJsonObject
			val results = JsonArray()
			root.getAsJsonArray("results")?.forEach { item ->
				if (results.size() >= 8) return@forEach
				val result = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
				val link = result.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
				if (!link.startsWith("https://") && !link.startsWith("http://")) return@forEach
				results.add(JsonObject().apply {
					addProperty("title", result.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.take(200).orEmpty())
					addProperty("url", link.take(2000))
					addProperty("snippet", result.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.take(600).orEmpty())
					result.get("publishedDate")?.takeIf { it.isJsonPrimitive }?.asString?.take(80)?.let { addProperty("published_date", it) }
				})
			}
			return ToolSupport.gson.toJson(JsonObject().apply {
				addProperty("tool", "web_search")
				addProperty("query", query)
				add("results", results)
			})
		}
	}
}
