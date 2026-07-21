package org.qo.services.inventoryServices

import com.google.gson.JsonObject
import org.qo.datas.Nodes
import org.qo.services.messageServices.Msg
import org.qo.utils.AuthTokens
import org.qo.utils.Funcs
import org.qo.utils.ReturnInterface
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/inventory")
class InventoryViewController(
	private val service: InventoryViewRequestService,
	private val nodes: Nodes,
	private val funcs: Funcs,
	private val ri: ReturnInterface
) {
	@RequestMapping("/request", method = [RequestMethod.GET, RequestMethod.POST])
	fun request(
		@RequestParam name: String,
		@RequestParam from: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		if (!isSurvivalServer(token, authorization)) return unauthorized()
		if (!isUsername(name) || !isUsername(from) || name.equals(from, ignoreCase = true)) {
			return error(HttpStatus.BAD_REQUEST, 1, "玩家名无效。")
		}
		val request = service.create(name, from)
			?: return error(HttpStatus.CONFLICT, 1, "已有相同请求，或待处理请求过多。")
		Msg.putSys("$from 发起了查看 $name 背包的请求。如需批准，请向申请者确认 32 位密钥后输入 /approve <密钥>。")
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", 0)
			addProperty("key", request.secret)
		}.toString())
	}

	@GetMapping("/query")
	fun query(
		@RequestParam secrets: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		if (!isSurvivalServer(token, authorization)) return unauthorized()
		val request = service.status(secrets)
			?: return error(HttpStatus.NOT_FOUND, 1, "请求不存在或已过期。")
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", 0)
			addProperty("approved", if (request.approved) 0 else 1)
			addProperty("viewer", request.viewer)
		}.toString())
	}

	@PostMapping("/consume")
	fun consume(
		@RequestParam secret: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		if (!isSurvivalServer(token, authorization)) return unauthorized()
		val request = service.consume(secret)
			?: return error(HttpStatus.NOT_FOUND, 1, "已批准的请求不存在或已被使用。")
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", 0)
			addProperty("viewer", request.viewer)
			addProperty("owner", request.owner)
		}.toString())
	}

	@RequestMapping("/validate")
	fun validate(
		@RequestParam(required = false) secret: String?,
		@RequestParam(required = false) key: String?,
		@RequestParam(required = false) auth: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolved = AuthTokens.resolve(auth, authorization)
		val authorized = resolved != null && runCatching { funcs.verify(resolved, Funcs.Perms.FULL) }.getOrDefault(false)
		if (!authorized) return unauthorized()
		val requestKey = secret ?: key ?: return error(HttpStatus.BAD_REQUEST, 1, "缺少请求密钥。")
		val request = service.approve(requestKey)
			?: return error(HttpStatus.NOT_FOUND, 1, "请求不存在或已过期。")
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", 0)
			addProperty("owner", request.owner)
			addProperty("viewer", request.viewer)
		}.toString())
	}

	private fun isSurvivalServer(token: String?, authorization: String?): Boolean {
		val resolved = AuthTokens.resolve(token, authorization) ?: return false
		return nodes.getServerFromToken(resolved) == SURVIVAL_SERVER_ID
	}

	private fun isUsername(value: String): Boolean = USERNAME.matches(value)

	private fun unauthorized(): ResponseEntity<String> = error(HttpStatus.UNAUTHORIZED, 401, "未授权。")

	private fun error(status: HttpStatus, code: Int, message: String): ResponseEntity<String> =
		ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", code)
			addProperty("message", message)
		}.toString(), status)

	companion object {
		private const val SURVIVAL_SERVER_ID = 1
		private val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
	}
}
