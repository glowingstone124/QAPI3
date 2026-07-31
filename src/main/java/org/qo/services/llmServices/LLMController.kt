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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/asking")
class LLMController(private val llmServices: LLMServices) {
	@PostMapping("/v1/chat/completions", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
	suspend fun chatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String,
	): Any {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val stream = runCatching {
			com.google.gson.JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean == true
		}.getOrDefault(false)
		val useModel = LLMServices.MODELS.entries
			.find { it.name == model }
			?: return jsonResponse("""{"error":{"message":"请求的模型不存在","type":"invalid_model","code":"invalid_model"}}""", HttpStatus.BAD_REQUEST)
		return if (stream) {
			streamResponse(body, requestToken, useModel)
		} else {
			val result = runCatching { llmServices.completeChat(body, requestToken, useModel) }.getOrElse {
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
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val result = runCatching { llmServices.completeBotChat(body, requestToken, qqUid, qqGroupId, qqName, model) }.getOrElse {
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
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val useModel = LLMServices.MODELS.entries
			.find { it.name == model }
			?: return jsonResponse("""{"error":{"message":"请求的模型不存在","type":"invalid_model","code":"invalid_model"}}""", HttpStatus.BAD_REQUEST)
		val result = runCatching { llmServices.completeMinecraftChat(body, requestToken, minecraftName, minecraftDim, minecraftHP, useModel) }.getOrElse {
			LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
		}
		return jsonResponse(result.body, HttpStatus.valueOf(result.status))
	}

	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleResponse(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String
	): Flow<ServerSentEvent<String>> {
		val requestToken = AuthTokens.resolve(token, authorization)
		if (requestToken.isNullOrBlank()) {
			return flowOf(sse("缺少或无效的令牌", "error"))
		}
		val useModel = LLMServices.MODELS.entries
			.find { it.name == model }
			?: return flowOf(sse("请求的模型不存在","error"))
		val requestBody = llmServices.buildPromptRequest(body, true, useModel)
		return streamEvents(requestBody, requestToken, useModel)
	}

	private fun streamResponse(body: String, token: String, model: LLMServices.MODELS): Flow<ServerSentEvent<String>> {
		return streamEvents(body, token, model)
	}

	private fun streamEvents(body: String, token: String, model: LLMServices.MODELS): Flow<ServerSentEvent<String>> = flow {
		try {
			val result = llmServices.streamChat(body, token, model)
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
