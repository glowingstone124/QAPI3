package org.qo.services.llmServices

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/asking")
class LLMConversationController(
	private val llmServices: LLMServices,
	private val kotshiConversationService: KotshiConversationService,
) {
	private val gson = GsonBuilder()
		.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
		.disableHtmlEscaping()
		.create()

	private fun jsonResponse(body: String, status: HttpStatus): ResponseEntity<String> {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body)
	}

	@GetMapping("/v1/conversations", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun listConversations(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): ResponseEntity<String> {
		val requestToken = AuthTokens.resolve(token, authorization)
			?: return jsonResponse(
				"""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse(
				"""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
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
			?: return jsonResponse(
				"""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse(
				"""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val json = runCatching { JsonParser.parseString(body.orEmpty()).asJsonObject }.getOrNull()
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
			?: return jsonResponse(
				"""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse(
				"""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
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
			?: return jsonResponse(
				"""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse(
				"""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val deleted = kotshiConversationService.deleteConversation(user.qqUid, id)
		llmServices.conversationService.delete("web:${user.qqUid}:${id.trim()}")
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
			?: return jsonResponse(
				"""{"error":{"message":"缺少或无效的令牌","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val user = llmServices.authenticateWeb(requestToken)
			?: return jsonResponse(
				"""{"error":{"message":"权限验证失败","type":"invalid_token","code":"invalid_token"}}""",
				HttpStatus.UNAUTHORIZED
			)
		val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
		val title = json?.get("title")?.takeIf { !it.isJsonNull }?.asString
		val model = json?.get("model")?.takeIf { !it.isJsonNull }?.asString
		val updated = kotshiConversationService.updateConversation(user.qqUid, id, title, model)
		return ResponseEntity.ok("""{"success":$updated}""")
	}
}
