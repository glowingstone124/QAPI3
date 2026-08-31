package org.qo.services.llmServices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
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
		@RequestHeader("X-Request-ID", required = false) requestId: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String,
	): ResponseEntity<*> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val stream = runCatching {
			com.google.gson.JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean == true
		}.getOrDefault(false)
		val useModel = llmServices.modelPresetFromRequest(model)
			?: return jsonResponse("""{"error":{"message":"请求的模型不存在","type":"invalid_model","code":"invalid_model"}}""", HttpStatus.BAD_REQUEST)
		return if (stream) {
			val result = runCatching { llmServices.streamChat(body, requestToken, useModel, requestId) }.getOrElse {
				LLMStreamResult(400, flowOf("""{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}"""))
			}
			if (result.status >= 400) {
				jsonResponse(result.chunks.firstOrNull().orEmpty(), HttpStatus.valueOf(result.status), result.quota)
			} else {
				streamResponse(result)
			}
		} else {
			val result = runCatching { llmServices.completeChat(body, requestToken, useModel, requestId) }.getOrElse {
				LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
			}
			jsonResponse(result.body, HttpStatus.valueOf(result.status), result.quota)
		}
	}

	@GetMapping("/v1/quota", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun quota(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val result = llmServices.quotaStatus(requestToken)
		return jsonResponse(result.body, HttpStatus.valueOf(result.status), result.quota)
	}

	@PostMapping("/v1/chat/completions/bot", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun botChatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("X-QQ-UID") qqUid: Long,
		@RequestHeader("X-QQ-Group-ID", required = false) qqGroupId: Long?,
		@RequestHeader("X-QQ-Name", required = false) qqName: String?,
		@RequestHeader("X-QQ-Message-ID", required = false) qqMessageId: Long?,
		@RequestHeader("X-Request-ID", required = false) requestId: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val result = runCatching {
			llmServices.completeBotChat(body, requestToken, qqUid, qqGroupId, qqName, qqMessageId, model, requestId)
		}.getOrElse {
			LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
		}
		return jsonResponse(result.body, HttpStatus.valueOf(result.status), result.quota)
	}

	@PostMapping("/v1/chat/history", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun archiveBotChatHistory(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("X-QQ-Group-ID") qqGroupId: Long,
		@RequestBody body: String,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val result = runCatching { llmServices.archiveBotChatHistory(requestToken, qqGroupId, body) }.getOrElse {
			LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
		}
		return jsonResponse(result.body, HttpStatus.valueOf(result.status), result.quota)
	}

	@PostMapping("/v1/chat/completions/minecraft", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun minecraftChatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("X-Minecraft-Name") minecraftName: String,
		@RequestHeader("X-Minecraft-Coordinate") minecraftDim: String,
		@RequestHeader("X-Minecraft-HP") minecraftHP: String,
		@RequestHeader("X-Request-ID", required = false) requestId: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val useModel = llmServices.modelPresetFromRequest(model)
			?: return jsonResponse("""{"error":{"message":"请求的模型不存在","type":"invalid_model","code":"invalid_model"}}""", HttpStatus.BAD_REQUEST)
		val result = runCatching {
			llmServices.completeMinecraftChat(body, requestToken, minecraftName, minecraftDim, minecraftHP, useModel, requestId)
		}.getOrElse {
			LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
		}
		return jsonResponse(result.body, HttpStatus.valueOf(result.status), result.quota)
	}

	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	suspend fun handleResponse(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("X-Request-ID", required = false) requestId: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestBody body: String
	): ResponseEntity<*> {
		val requestToken = AuthTokens.resolve(token, authorization)
		if (requestToken.isNullOrBlank()) {
			return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		}
		val useModel = llmServices.modelPresetFromRequest(model)
			?: return jsonResponse("""{"error":{"message":"请求的模型不存在","type":"invalid_model","code":"invalid_model"}}""", HttpStatus.BAD_REQUEST)
		val requestBody = llmServices.buildPromptRequest(body, true, useModel)
		val result = llmServices.streamChat(requestBody, requestToken, useModel, requestId)
		if (result.status >= 400) {
			return jsonResponse(result.chunks.firstOrNull().orEmpty(), HttpStatus.valueOf(result.status), result.quota)
		}
		return streamResponse(result)
	}

	private fun streamResponse(result: LLMStreamResult): ResponseEntity<Flow<ServerSentEvent<String>>> {
		val builder = ResponseEntity.status(result.status).contentType(MediaType.TEXT_EVENT_STREAM)
		applyQuotaHeaders(builder, result.quota, result.status)
		return builder.body(streamEvents(result.chunks))
	}

	private fun streamEvents(chunks: Flow<String>): Flow<ServerSentEvent<String>> = flow {
		try {
			chunks.collect { chunk ->
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

	private fun jsonResponse(body: String, status: HttpStatus, quota: LLMQuotaView? = null): ResponseEntity<String> {
		val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
		applyQuotaHeaders(builder, quota, status.value())
		return builder.body(body)
	}

	private fun applyQuotaHeaders(builder: ResponseEntity.BodyBuilder, quota: LLMQuotaView?, status: Int) {
		if (quota == null) return
		builder.header("X-RateLimit-Limit", quota.limit.toString())
		builder.header("X-RateLimit-Remaining", quota.remaining.toString())
		builder.header("X-RateLimit-Reset", quota.resetAtEpochSeconds.toString())
		if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
			val retryAfter = (quota.resetAtEpochSeconds - System.currentTimeMillis() / 1000L).coerceAtLeast(1L)
			builder.header(HttpHeaders.RETRY_AFTER, retryAfter.toString())
		}
	}
}
