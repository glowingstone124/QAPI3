package org.qo.services.llmServices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/asking")
class LLMController(private val llmServices: LLMServices) {
	@PostMapping("/v1/chat/completions", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
	suspend fun chatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String
	): Any {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val stream = runCatching {
			com.google.gson.JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean == true
		}.getOrDefault(false)

		return if (stream) {
			streamResponse(body, requestToken)
		} else {
			val result = runCatching { llmServices.completeChat(body, requestToken) }.getOrElse {
				LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
			}
			jsonResponse(result.body, HttpStatus.valueOf(result.status))
		}
	}

	@PostMapping("/v1/chat/completions/bot", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun botChatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("X-QQ-UID") qqUid: Long,
		@RequestHeader("X-QQ-Group-ID", required = false) qqGroupId: Long?,
		@RequestHeader("X-QQ-Name", required = false) qqName: String?,
		@RequestBody body: String
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val result = runCatching { llmServices.completeBotChat(body, requestToken, qqUid, qqGroupId, qqName) }.getOrElse {
			LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
		}
		return jsonResponse(result.body, HttpStatus.valueOf(result.status))
	}

	@PostMapping("/v1/chat/completions/minecraft", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun minecraftChatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("X-Minecraft-Name") minecraftName: String,
		@RequestHeader("X-Minecraft-Coordinate") minecraftDim: String,
		@RequestHeader("X-Minecraft-HP") minecraftHP: String,
		@RequestBody body: String
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val result = runCatching { llmServices.completeMinecraftChat(body, requestToken, minecraftName, minecraftDim, minecraftHP) }.getOrElse {
			LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
		}
		return jsonResponse(result.body, HttpStatus.valueOf(result.status))
	}

	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleResponse(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String
	): Flow<ServerSentEvent<String>> {
		val requestToken = AuthTokens.resolve(token, authorization)
		if (requestToken.isNullOrBlank()) {
			return flowOf(sse("缺少或无效的令牌", "error"))
		}
		val requestBody = llmServices.buildPromptRequest(body, true)
		return streamEvents(requestBody, requestToken)
	}

	private fun streamResponse(body: String, token: String): Flow<ServerSentEvent<String>> {
		return streamEvents(body, token)
	}

	private fun streamEvents(body: String, token: String): Flow<ServerSentEvent<String>> = flow {
		try {
			val result = llmServices.streamChat(body, token)
			if (result.status >= 400) {
				result.chunks.collect { emit(sse(it, "error")) }
				return@flow
			}
			result.chunks.collect { chunk ->
				emit(sse(chunk))
			}
			emit(sse("[DONE]"))
		} catch (e: Exception) {
			emit(sse(e.message ?: "LLM stream failed", "error"))
		}
	}

	private fun sse(data: String, event: String? = null): ServerSentEvent<String> {
		val builder = ServerSentEvent.builder(data)
		if (event != null) builder.event(event)
		return builder.build()
	}

	private fun jsonResponse(body: String, status: HttpStatus): ResponseEntity<String> {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
	}
}
