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
import org.springframework.beans.factory.annotation.Value
import org.springframework.util.PatternMatchUtils
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/asking")
class LLMController(
	private val llmServices: LLMServices,
	private val kotshiConversationService: KotshiConversationService,
	@Value("\${qapi.llm.web-allowed-origin-patterns:https://*.qoriginal.vip,http://localhost:*,http://127.0.0.1:*}")
	allowedWebOriginPatterns: String,
) {
	private val gson = com.google.gson.GsonBuilder()
		.setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
		.disableHtmlEscaping()
		.create()
	private val allowedWebOriginPatterns = allowedWebOriginPatterns
		.split(',')
		.map(String::trim)
		.filter(String::isNotEmpty)
		.toTypedArray()

	@PostMapping("/v1/chat/completions", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
	suspend fun chatCompletions(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader(HttpHeaders.ORIGIN, required = false) origin: String?,
		@RequestHeader("X-Request-ID", required = false) requestId: String?,
		@RequestHeader("X-Conversation-ID", required = false) conversationIdHeader: String?,
		@RequestParam(name = "model", required = false, defaultValue = "fast") model: String = "fast",
		@RequestParam(name = "conversation_id", required = false) conversationIdParam: String?,
		@RequestBody body: String,
	): ResponseEntity<*> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)

		val conversationId = conversationIdParam?.takeIf { it.isNotBlank() }
			?: conversationIdHeader?.takeIf { it.isNotBlank() }
			?: runCatching {
				val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
				(obj.get("conversation_id") ?: obj.get("conversationId"))?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
			}.getOrNull()

		val stream = runCatching {
			com.google.gson.JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean == true
		}.getOrDefault(false)
		if (stream && !isAllowedWebOrigin(origin)) {
			return jsonResponse(
				"""{"error":{"message":"不允许的 Web 来源","type":"origin_not_allowed","code":"origin_not_allowed"}}""",
				HttpStatus.FORBIDDEN,
			)
		}
		val useModel = llmServices.modelPresetFromRequest(model)
			?: return jsonResponse("""{"error":{"message":"请求的模型不存在","type":"invalid_model","code":"invalid_model"}}""", HttpStatus.BAD_REQUEST)
		return if (stream) {
			val result = runCatching { llmServices.streamChat(body, requestToken, useModel, requestId, conversationId) }.getOrElse {
				LLMStreamResult(400, flowOf("""{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}"""))
			}
			if (result.status >= 400) {
				jsonResponse(result.chunks.firstOrNull().orEmpty(), HttpStatus.valueOf(result.status), result.quota)
			} else {
				streamResponse(result)
			}
		} else {
			val result = runCatching { llmServices.completeChat(body, requestToken, useModel, requestId, conversationId) }.getOrElse {
				LLMNonStreamResult(400, """{"error":{"message":"${it.message ?: "请求格式错误"}","type":"bad_request","code":"bad_request"}}""")
			}
			jsonResponse(result.body, HttpStatus.valueOf(result.status), result.quota)
		}
	}

	private fun isAllowedWebOrigin(origin: String?): Boolean {
		if (origin.isNullOrBlank() || allowedWebOriginPatterns.isEmpty()) return false
		return PatternMatchUtils.simpleMatch(allowedWebOriginPatterns, origin.trim())
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

	@GetMapping("/v1/conversations", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun listConversations(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse("""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val list = kotshiConversationService.listConversations(user.qqUid)
		return ResponseEntity.ok(gson.toJson(list))
	}

	@PostMapping("/v1/conversations", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun createConversation(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody(required = false) body: String?,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse("""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val json = runCatching { com.google.gson.JsonParser.parseString(body.orEmpty()).asJsonObject }.getOrNull()
		val title = json?.get("title")?.takeIf { !it.isJsonNull }?.asString
		val model = json?.get("model")?.takeIf { !it.isJsonNull }?.asString ?: "fast"
		val customId = json?.get("id")?.takeIf { !it.isJsonNull }?.asString
		val conv = kotshiConversationService.createConversation(user.qqUid, title, model, customId)
		return ResponseEntity.ok(gson.toJson(conv))
	}

	@GetMapping("/v1/conversations/{id}/messages", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun getConversationMessages(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@PathVariable("id") id: String,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse("""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val messages = kotshiConversationService.getMessages(user.qqUid, id)
		return ResponseEntity.ok(gson.toJson(messages))
	}

	@DeleteMapping("/v1/conversations/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun deleteConversation(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@PathVariable("id") id: String,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse("""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val deleted = kotshiConversationService.deleteConversation(user.qqUid, id)
		return ResponseEntity.ok("""{"success":$deleted}""")
	}

	@PatchMapping("/v1/conversations/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun updateConversation(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@PathVariable("id") id: String,
		@RequestBody body: String,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse("""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse("""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""", HttpStatus.UNAUTHORIZED)
		val json = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull()
		val title = json?.get("title")?.takeIf { !it.isJsonNull }?.asString
		val model = json?.get("model")?.takeIf { !it.isJsonNull }?.asString
		val updated = kotshiConversationService.updateConversation(user.qqUid, id, title, model)
		return ResponseEntity.ok("""{"success":$updated}""")
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
		builder.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
		builder.header("X-Accel-Buffering", "no")
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
