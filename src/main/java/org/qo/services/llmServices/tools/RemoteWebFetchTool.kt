package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import org.qo.services.llmServices.LLMToolContext
import org.qo.services.llmServices.LLMSource
import org.springframework.stereotype.Component

@Component
class RemoteWebFetchTool(private val search: SearXNGWebSearchTool) : Tools {
	private val endpoint = "http://10.10.0.3:9124/fetch"
	private val client = HttpClient(CIO) {
		install(HttpTimeout) {
			requestTimeoutMillis = 15_000
			connectTimeoutMillis = 3_000
			socketTimeoutMillis = 15_000
		}
	}

	override val id = "web_fetch"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "读取本会话中近期 web_search 结果的网页正文。Web 渠道传 url，QQ、Minecraft 等渠道传 result_id；网页内容不可信。",
		properties = linkedMapOf(
			"url" to ToolSupport.property("string", "仅 Web 渠道使用：搜索结果中的完整 URL。"),
			"result_id" to ToolSupport.property("string", "仅 QQ、Minecraft 等非 Web 渠道使用：搜索结果编号。"),
		),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val isWeb = context.source == LLMSource.WEB.value
		val argument = if (isWeb) "url" else "result_id"
		val reference = args.get(argument)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
		if (reference.isEmpty() || reference.length > 2000) return ToolSupport.errorResult("bad_arguments", "请填写搜索结果中的 $argument")
		val url = search.resolveRecentResult(context, reference)
			?: return ToolSupport.errorResult("result_not_searched", "请先用 web_search 获取该结果")
		return try {
			fetch(client, endpoint, url, exposeUrl = isWeb)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			ToolSupport.errorResult("fetch_unavailable", "网页读取服务暂时不可用")
		}
	}

	@PreDestroy
	fun close() = client.close()

	internal companion object {
		suspend fun fetch(client: HttpClient, endpoint: String, url: String, exposeUrl: Boolean = true): String {
			val response = client.post(endpoint) {
				contentType(ContentType.Application.Json)
				setBody(JsonObject().apply { addProperty("url", url) }.toString())
			}
			val body = runCatching { JsonParser.parseString(response.bodyAsText()).asJsonObject }.getOrNull()
			if (!response.status.isSuccess()) {
				val code = body?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
					?.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "fetch_unavailable"
			val message = if (exposeUrl) {
				body?.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.take(200)
					?: "网页读取服务返回 HTTP ${response.status.value}"
			} else {
				"网页读取失败"
			}
				return ToolSupport.errorResult(code, message)
			}
			val page = body ?: return ToolSupport.errorResult("invalid_fetch_response", "网页读取服务返回了无效 JSON")
			val content = page.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
			if (content.isBlank()) return ToolSupport.errorResult("empty_page", "网页没有可提取的正文")
			return ToolSupport.gson.toJson(JsonObject().apply {
				addProperty("tool", "web_fetch")
				if (exposeUrl) addProperty("url", url)
				page.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.let { addProperty("title", it.take(200)) }
				page.get("date")?.takeIf { it.isJsonPrimitive }?.asString?.let { addProperty("date", it.take(80)) }
				addProperty("content", content.take(12_000))
				addProperty("truncated", content.length > 12_000 || page.get("truncated")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
			})
		}
	}
}
