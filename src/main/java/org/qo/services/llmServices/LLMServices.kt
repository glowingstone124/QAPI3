package org.qo.services.llmServices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class LLMServices(private val authorityNeededServicesImpl: AuthorityNeededServicesImpl){
	val redis = Redis()
	val client = HttpClient(CIO)
	val token = Files.readString(Path.of("LLMAPITOKEN")).trim()
	suspend fun generalPreProcess(token: String) : Boolean {
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
			val channel = response.bodyAsChannel()
			while (!channel.isClosedForRead) {
				val line = channel.readUTF8Line()
				if (line == null) break
				if (line.startsWith("data: ")) {
					val dataJson = line.removePrefix("data: ").trim()
					if (dataJson == "[DONE]") break
					emit(dataJson)
				}
			}
		} else {
			throw RuntimeException("API 请求失败: ${response.status}")
		}
	}

	suspend fun generateLLMStream(prompt: String, token: String): Pair<Flow<String>?, Boolean> {
		if (hasAlreadyRequested(token)) {
			return Pair(null, false)
		}

		return Pair(accessOpenAI(prompt), true)
	}

	private fun hasAlreadyRequested(token: String): Boolean {
		val result = redis.exists(token, DatabaseType.QO_ASSISTANT_DATABASE.value).ignoreException()
		result?.let {
			if (!it) {
				redis.insert(token, "true", DatabaseType.QO_ASSISTANT_DATABASE.value, expires = 2)
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
data class ChatRequest(val model: String,val messages: List<Message>, val stream: Boolean)