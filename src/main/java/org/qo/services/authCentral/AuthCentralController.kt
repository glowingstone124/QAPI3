package org.qo.services.authCentral

import com.google.gson.JsonObject
import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class TicketGrantBody(
	val service: String? = null
)

data class ServiceValidateBody(
	val ticket: String? = null,
	val service: String? = null
)

@RestController
@RequestMapping("/qo/auth")
class AuthCentralController(
	private val authCentralService: AuthCentralService,
) {
	@PostMapping("/ticket/grant", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun grantTicket(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestHeader("token", required = false) tokenHeader: String?,
		@RequestBody body: TicketGrantBody,
	): ResponseEntity<String> {
		val callerToken = AuthTokens.resolve(tokenHeader, authorization)
		return when (val result = authCentralService.grantTicket(callerToken, body.service)) {
			is TicketGrantResult.Success -> response(HttpStatus.OK) {
				addProperty("result", true)
				addProperty("ticket", result.ticket)
				addProperty("redirectUrl", result.redirectUrl)
				addProperty("expiresIn", result.expiresInSeconds)
			}
			TicketGrantResult.Unauthorized -> grantFailure(HttpStatus.UNAUTHORIZED, "未提供有效凭据或当前会话已失效，请重新登录")
			TicketGrantResult.MissingService -> grantFailure(HttpStatus.BAD_REQUEST, "缺少 service 参数")
			TicketGrantResult.UntrustedService -> grantFailure(HttpStatus.BAD_REQUEST, "未受信任的重定向服务地址")
		}
	}

	@PostMapping("/serviceValidate", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun serviceValidatePost(
		@RequestBody body: ServiceValidateBody
	): ResponseEntity<String> = handleValidate(body.ticket, body.service)

	@GetMapping("/serviceValidate", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun serviceValidateGet(
		@RequestParam(required = false) ticket: String?,
		@RequestParam(required = false) service: String?
	): ResponseEntity<String> = handleValidate(ticket, service)

	private suspend fun handleValidate(ticket: String?, service: String?): ResponseEntity<String> {
		return when (val result = authCentralService.validateTicket(ticket, service)) {
			is ServiceValidateResult.Success -> response(HttpStatus.OK) {
				addProperty("success", true)
				addProperty("user", result.user)
				if (result.uid != null) {
					addProperty("uid", result.uid)
				}
				addProperty("token", result.token)
				addProperty("accountType", result.accountType)

				val attributes = JsonObject().apply {
					addProperty("score", result.score)
					addProperty("frozen", result.frozen)
				}
				add("attributes", attributes)
			}
			ServiceValidateResult.MissingParameters -> validationFailure(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "缺少 ticket 或 service 参数")
			ServiceValidateResult.InvalidTicket -> validationFailure(HttpStatus.UNAUTHORIZED, "INVALID_TICKET", "票据无效、已过期或与目标服务不匹配")
			ServiceValidateResult.AccountFrozen -> validationFailure(HttpStatus.FORBIDDEN, "ACCOUNT_FROZEN", "绑定的 QO 账号已被冻结")
		}
	}

	private fun grantFailure(status: HttpStatus, message: String): ResponseEntity<String> = response(status) {
		addProperty("result", false)
		addProperty("message", message)
	}

	private fun validationFailure(status: HttpStatus, code: String, message: String): ResponseEntity<String> = response(status) {
		addProperty("success", false)
		addProperty("code", code)
		addProperty("message", message)
	}

	private fun response(status: HttpStatus, build: JsonObject.() -> Unit): ResponseEntity<String> =
		ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(JsonObject().apply(build).toString())
}
