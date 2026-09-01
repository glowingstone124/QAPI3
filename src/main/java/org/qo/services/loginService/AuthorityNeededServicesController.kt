package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.datas.GsonProvider.gson
import org.qo.datas.Mapping
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.qo.utils.AuthTokens
import org.qo.services.loginService.IPWhitelistServices.WhitelistReasons
import org.qo.services.llmServices.KotshiAccountService
import org.qo.orm.UserORM
import org.qo.utils.SerializeUtils.convertToJsonArray
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

val userORM = UserORM()

@RestController
@RequestMapping("/qo/authorization")
class AuthorityNeededServicesController(
	private val login: Login,
	private val ri: ReturnInterface,
	private val ipWhitelistServices: IPWhitelistServices,
	private val authorityNeededServicesImpl: AuthorityNeededServicesImpl,
	private val playerCardCustomizationImpl: PlayerCardCustomizationImpl,
	private val affiliatedAccountServices: AffiliatedAccountServices,
	private val nodes: Nodes,
	private val recentLoginService: RecentLoginService,
	private val kotshiAccountService: KotshiAccountService,
) {
	private fun resolveLoginToken(tokenHeader: String?, authorizationHeader: String?): String? {
		return AuthTokens.resolve(tokenHeader, authorizationHeader)
	}

	private fun missingTokenResponse(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(Return(1, "Missing token.").serialized())
	}

	@PostMapping("/account/frozen")
	suspend fun frozenQOAccount(@RequestHeader authorization: String, @RequestParam uid: Long): ResponseEntity<String> {
		val resolvedToken = AuthTokens.resolve(null, authorization) ?: return missingTokenResponse()
		if(authorityNeededServicesImpl.frozenQOAccount(resolvedToken, uid)) {
			return ri.GeneralHttpHeader("ok")
		} else {
			return ri.GeneralHttpHeader("Failed")
		}
	}

	@PostMapping("/message/upload")
	suspend fun insertWebMessage(
		@RequestBody msg: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val (code, result) = authorityNeededServicesImpl.insertWebMessage(msg, resolvedToken)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", code)
			addProperty("result", result)
		}.toString())
	}

	@GetMapping("/account")
	suspend fun getAccountInfo(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		return ri.GeneralHttpHeader(authorityNeededServicesImpl.getAccountInfo(resolvedToken))
	}

	@GetMapping("/account/kotshi")
	suspend fun getKotshiAccount(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val snapshot = kotshiAccountService.snapshot(resolvedToken)
			?: return ri.GeneralHttpHeader("{\"error\":\"Invalid token.\"}", HttpStatus.UNAUTHORIZED)
		return ri.GeneralHttpHeader(snapshot.toJson())
	}

	@PatchMapping("/account/kotshi")
	suspend fun updateKotshiAccount(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String,
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
		val enabled = (json?.get("kotshi_query_enabled") ?: json?.get("query_enabled"))
			?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
			?.asBoolean
			?: return ri.GeneralHttpHeader("{\"error\":\"kotshi_query_enabled must be boolean\"}", HttpStatus.BAD_REQUEST)
		val settings = kotshiAccountService.updateQueryEnabled(resolvedToken, enabled)
			?: return ri.GeneralHttpHeader("{\"error\":\"Invalid token.\"}", HttpStatus.UNAUTHORIZED)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("kotshi_query_enabled", settings.queryEnabled)
		}.toString())
	}

	@PostMapping("/account/card/custom")
	suspend fun uploadCardDiff(
		@RequestBody jsonBody: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val card = gson.fromJson(jsonBody, Mapping.CardProfile::class.java)
		val result = playerCardCustomizationImpl.updatePlayerAccountCardInfo(resolvedToken, card)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("result", result.first)
			addProperty("message", result.second)
		}.toString())
	}

	@GetMapping("/account/card")
	suspend fun getAccountCardInfo(@RequestParam profileUuid: String): ResponseEntity<String> {
		val result = playerCardCustomizationImpl.getProfileDetailAsync(profileUuid)
		if (result == null) {
			return ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("error", "no profile found")
				addProperty("code", "1")
			}.toString())
		}
		val jsonObj = JsonParser.parseString(result).asJsonObject.apply { addProperty("code", 0) }
		return ri.GeneralHttpHeader(jsonObj.toString())
	}

	@GetMapping("/ip/query")
	suspend fun getIpInfo(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		return ri.GeneralHttpHeader(authorityNeededServicesImpl.getIpWhitelists(resolvedToken))
	}

	@GetMapping("/ip/add")
	suspend fun insertIntoIpWhitelist(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam ip: String
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val (username, errorCode) = login.validate(resolvedToken)
		if (authorityNeededServicesImpl.doPrecheck(username, errorCode) != null || username == null) {
			return ri.GeneralHttpHeader(Return(1, authorityNeededServicesImpl.getErrorMessage(1)).serialized())
		}
		return when (ipWhitelistServices.joinWhitelist(ip, resolvedToken)) {
			WhitelistReasons.SUCCESS -> ri.GeneralHttpHeader(Return(0, "ok").serialized())
			WhitelistReasons.TOKEN_INVALID -> ri.GeneralHttpHeader(
				Return(
					1,
					authorityNeededServicesImpl.getErrorMessage(1) + "(else)"
				).serialized()
			)

			WhitelistReasons.IP_WHITELIST_FULL -> ri.GeneralHttpHeader(Return(2, "Too many ips").serialized())
			WhitelistReasons.IP_NOT_FOUND -> ri.GeneralHttpHeader(Return(3, "IP not whitelisted").serialized())
			WhitelistReasons.INVALID_IP -> ri.GeneralHttpHeader(Return(4, "Invalid IP address").serialized())
		}
	}

	@DeleteMapping("/ip/remove")
	suspend fun removeFromIpWhitelist(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam ip: String
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val (username, errorCode) = login.validate(resolvedToken)
		if (authorityNeededServicesImpl.doPrecheck(username, errorCode) != null || username == null) {
			return ri.GeneralHttpHeader(Return(1, authorityNeededServicesImpl.getErrorMessage(1)).serialized())
		}
		return when (ipWhitelistServices.leaveWhitelist(ip, resolvedToken)) {
			WhitelistReasons.SUCCESS -> ri.GeneralHttpHeader(Return(0, "ok").serialized())
			WhitelistReasons.TOKEN_INVALID -> ri.GeneralHttpHeader(
				Return(
					1,
					authorityNeededServicesImpl.getErrorMessage(1) + "(else)"
				).serialized()
			)

			WhitelistReasons.IP_WHITELIST_FULL -> ri.GeneralHttpHeader(Return(2, "Too many ips").serialized())
			WhitelistReasons.IP_NOT_FOUND -> ri.GeneralHttpHeader(Return(3, "IP not whitelisted").serialized())
			WhitelistReasons.INVALID_IP -> ri.GeneralHttpHeader(Return(4, "Invalid IP address").serialized())
		}
	}

	@GetMapping("/fortune")
	suspend fun getFortuneForUser(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		return ri.GeneralHttpHeader(authorityNeededServicesImpl.calculateFortune(resolvedToken))
	}

	@PostMapping("/auto-login")
	fun autoLogin(
		@RequestHeader("Token", required = false) token: String?,
		@RequestBody request: AutoLoginRequest
	): ResponseEntity<String> {
		if (token == null || nodes.getServerFromToken(token) < 0) {
			return ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("ok", false)
				addProperty("error", "unauthorized")
			}.toString(), HttpStatus.UNAUTHORIZED)
		}

		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("ok", recentLoginService.canAutoLogin(request.username, request.ip))
		}.toString())
	}

	@GetMapping("/cards/obtained")
	suspend fun getPlayerCardList(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val returnObj = JsonObject()
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		val (username, errorCode) = login.validate(resolvedToken)
		if (authorityNeededServicesImpl.doPrecheck(username, errorCode) != null) {
			return ri.GeneralHttpHeader(returnObj.apply {
				addProperty("error", "invalid username")
			}.toString())
		}
		return ReturnInterface().GeneralHttpHeader(
			playerCardCustomizationImpl
				.getPlayerCardListAsJsonAsync(username!!)
				.toString()
		)
	}

	@GetMapping("/cards/info")
	suspend fun getCardInfo(@RequestParam id: Long): ResponseEntity<String> {
		val returnObj = JsonObject()
		val result = playerCardCustomizationImpl.getCardInformationAsync(id) ?: return ri.GeneralHttpHeader(
			returnObj.apply {
				addProperty("error", "card not found")
			}.toString()
		)
		return ri.GeneralHttpHeader(result.toString())
	}

	@GetMapping("/cards/all")
	suspend fun getAllCards(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(playerCardCustomizationImpl.getAllCardsAsync().convertToJsonArray().toString())
	}

	@GetMapping("/avatars/all")
	suspend fun getAllAvatars(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(playerCardCustomizationImpl.getAllAvatars().convertToJsonArray().toString())
	}

	@GetMapping("/affiliated/query")
	suspend fun getAffiliatedAccount(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		return ri.GeneralHttpHeader(affiliatedAccountServices.getAffiliatedAccount(resolvedToken).convertToJsonArray().toString())
	}

	@PostMapping("/affiliated/add")
	suspend fun addAffiliatedAccount(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		return ri.GeneralHttpHeader(affiliatedAccountServices.addAffiliatedAccount(resolvedToken, body).toHumanReadableJson())
	}

	@DeleteMapping("/affiliated/remove")
	suspend fun removeAffiliatedAccount(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam name: String
	): ResponseEntity<String> {
		val resolvedToken = resolveLoginToken(token, authorization) ?: return missingTokenResponse()
		return ri.GeneralHttpHeader(
			affiliatedAccountServices.removeAffiliatedAccount(resolvedToken, name).toHumanReadableJson()
		)
	}
}

data class Return(
	val code: Int,
	val reason: String
) {
	fun serialized(): String {
		val gson = Gson()
		return gson.toJson(this)
	}
}

data class AutoLoginRequest(
	val username: String?,
	val ip: String?
)

fun Boolean.toHumanReadableJson(): String {
	return JsonObject().apply { addProperty("result", this@toHumanReadableJson) }.toString()
}
