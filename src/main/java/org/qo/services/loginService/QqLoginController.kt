package org.qo.services.loginService

import com.google.gson.JsonObject
import org.qo.datas.Nodes
import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class QqLoginStartBody(val qq: String? = null)
data class QqLoginConfirmBody(val qq: Long? = null, val code: String? = null)

@RestController
@RequestMapping("/qo/game/qq-login")
class QqLoginController(
	private val service: QqLoginService,
	private val nodes: Nodes,
) {
	@PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	fun start(@RequestBody body: QqLoginStartBody): ResponseEntity<String> {
		val qq = body.qq?.trim()?.takeIf { it.matches(Regex("\\d{5,12}")) }?.toLongOrNull()
			?: return response(HttpStatus.BAD_REQUEST) {
				addProperty("message", "请输入有效的 QQ 号")
			}
		val challenge = runCatching { service.start(qq) }.getOrElse {
			return response(HttpStatus.SERVICE_UNAVAILABLE) {
				addProperty("message", "暂时无法生成 QQ 登录代码")
			}
		}
		return response(HttpStatus.CREATED) {
			addProperty("request_id", challenge.requestId)
			addProperty("code", challenge.code)
			addProperty("expires_in", ((challenge.expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L))
		}
	}

	@GetMapping("/{requestId}", produces = [MediaType.APPLICATION_JSON_VALUE])
	fun status(@PathVariable requestId: String): ResponseEntity<String> {
		val challenge = service.status(requestId)
			?: return response(HttpStatus.GONE) { addProperty("status", "expired") }
		if (challenge.expiresAt <= System.currentTimeMillis()) {
			return response(HttpStatus.GONE) { addProperty("status", "expired") }
		}
		return response(HttpStatus.OK) {
			addProperty("status", challenge.status)
			if (challenge.status == "authorized") {
				addProperty("token", challenge.token)
				addProperty("username", challenge.username)
				addProperty("account_type", challenge.accountType)
				addProperty("daily_limit", challenge.dailyLimit)
			}
		}
	}

	@PostMapping("/confirm", produces = [MediaType.APPLICATION_JSON_VALUE])
	suspend fun confirm(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: QqLoginConfirmBody,
	): ResponseEntity<String> {
		val serverToken = AuthTokens.resolve(token, authorization)
		if (serverToken == null || nodes.getServerFromToken(serverToken) < 0) {
			return response(HttpStatus.UNAUTHORIZED) {
				addProperty("result", false)
				addProperty("message", "QBot 身份验证失败")
			}
		}
		val qq = body.qq ?: return response(HttpStatus.BAD_REQUEST) {
			addProperty("result", false)
			addProperty("message", "缺少 QQ 号")
		}
		val code = body.code?.trim().orEmpty()
		return when (val result = service.confirm(qq, code)) {
			is QqLoginConfirmation.Authorized -> response(HttpStatus.OK) {
				addProperty("result", true)
				addProperty("message", "Kotshi 登录验证成功")
				addProperty("account_type", result.accountType)
				addProperty("daily_limit", result.dailyLimit)
			}
			QqLoginConfirmation.NotFound -> failure(HttpStatus.NOT_FOUND, "登录代码不存在")
			QqLoginConfirmation.QqMismatch -> failure(HttpStatus.FORBIDDEN, "该登录代码不属于你的 QQ 号")
			QqLoginConfirmation.Expired -> failure(HttpStatus.GONE, "登录代码已过期")
			QqLoginConfirmation.AlreadyUsed -> failure(HttpStatus.CONFLICT, "登录代码已被使用")
			QqLoginConfirmation.AccountFrozen -> failure(HttpStatus.FORBIDDEN, "绑定的 QO 账号已被冻结")
		}
	}

	private fun failure(status: HttpStatus, message: String): ResponseEntity<String> = response(status) {
		addProperty("result", false)
		addProperty("message", message)
	}

	private fun response(status: HttpStatus, build: JsonObject.() -> Unit): ResponseEntity<String> =
		ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(JsonObject().apply(build).toString())
}
