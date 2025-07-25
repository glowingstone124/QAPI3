package org.qo.services.llmServices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class LLMServices(private val authorityNeededServicesImpl: AuthorityNeededServicesImpl) {
	val redis = Redis()
	val client = HttpClient(CIO) {
		install(ContentNegotiation) {
			json(Json {
				ignoreUnknownKeys = true
				prettyPrint = true
				isLenient = true
			})
		}
		install(HttpTimeout) {
			requestTimeoutMillis = 50 * 1000
		}
	}

	val token = Files.readString(Path.of("LLMAPITOKEN")).trim()
	suspend fun generalPreProcess(token: String): Boolean {
		val result = authorityNeededServicesImpl.internalAuthorityCheck(token)
		if (!result.second) {
			return false
		}
		result.first?.let {
			return (!it.frozen!!)
		}
		//This should never be reached
		return false
	}

	suspend fun prepareDocuments() {

	}

	suspend fun accessOpenAI(prompt: String): Flow<String> = flow {
		val response = client.post("https://api.deepseek.com/v1/chat/completions") {
			header(HttpHeaders.Authorization, "Bearer $token")
			contentType(ContentType.Application.Json)
			setBody(
				ChatRequest(
					model = "deepseek-chat",
					messages = listOf(
						Message("system", "You are a helpful assistant."),
						Message("user", prompt)
					),
					stream = true
				)
			)
		}

		if (response.status.isSuccess()) {
			val decoder = response.bodyAsChannel().toInputStream().bufferedReader()
			decoder.lineSequence().forEach { line ->
				if (!line.startsWith("data:")) return@forEach
				val data = line.removePrefix("data:").trim()
				if (data == "[DONE]") return@forEach

				try {
					val resp = Json { ignoreUnknownKeys = true }
						.decodeFromString<ChatCompletionChunk>(data)
					val content = resp.choices[0].delta.content
					if (!content.isNullOrBlank()) {
						emit(content)
						println("接收内容: $content")
					}
				} catch (e: Exception) {
					println("解析错误: ${e.message}")
				}
			}
		} else {
			throw RuntimeException("API 请求失败: ${response.status}")
		}
	}

	suspend fun generateLLMStream(prompt: String, token: String): Pair<Flow<String>?, Boolean> {
		if (hasAlreadyRequested(token) || !generalPreProcess(token)) {
			return Pair(null, false)
		}

		return Pair(accessOpenAI(prompt), true)
	}

	private fun hasAlreadyRequested(token: String): Boolean {
		val result = redis.exists(token, DatabaseType.QO_ASSISTANT_DATABASE.value).ignoreException()
		result?.let {
			if (!it) {
				redis.insert(token, "true", DatabaseType.QO_ASSISTANT_DATABASE.value, expires = 2).ignoreException()
				return false
			}
			return true
		}
		return false
	}
}

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatRequest(val model: String, val messages: List<Message>, val stream: Boolean)

@Serializable
data class ChatCompletionChunk(
	val id: String,
	val `object`: String,
	val created: Long,
	val model: String,
	val system_fingerprint: String,
	val choices: List<Choice>,
	val usage: Usage? = null
)

@Serializable
data class Choice(
	val index: Int,
	val delta: Delta,
	val logprobs: String? = null,
	val finish_reason: String? = null
)

@Serializable
data class Delta(
	val content: String? = null
)

@Serializable
data class Usage(
	val prompt_tokens: Int,
	val completion_tokens: Int,
	val total_tokens: Int,
	val prompt_tokens_details: PromptTokensDetails? = null,
	val prompt_cache_hit_tokens: Int? = null,
	val prompt_cache_miss_tokens: Int? = null
)

@Serializable
data class PromptTokensDetails(
	val cached_tokens: Int? = null
)
