package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.datas.GsonProvider.gson
import org.qo.datas.Mapping
import org.qo.utils.ReturnInterface
import org.qo.utils.AuthTokens
import org.qo.services.loginService.IPWhitelistServices.WhitelistReasons
import org.qo.orm.UserORM
import org.qo.utils.SerializeUtils.convertToJsonArray
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
	private val affiliatedAccountServices: AffiliatedAccountServices
) {
	private fun resolveLoginToken(tokenHeader: String?, authorizationHeader: String?): String? {
		return AuthTokens.resolve(tokenHeader, authorizationHeader)
	}

	private fun missingTokenResponse(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(Return(1, "Missing token.").serialized())
	}

	@PostMapping("/account/frozen")
	suspend fun frozenQOAccount(@RequestHeader authorization: String, @RequestParam uid: Long): ResponseEntity<String> {
		if(authorityNeededServicesImpl.frozenQOAccount(authorization, uid)) {
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
	fun getAccountCardInfo(@RequestParam profileUuid: String): ResponseEntity<String> {
		val result = playerCardCustomizationImpl.getProfileDetail(profileUuid)
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

	@GetMapping("/templogin")
	suspend fun getPlayerRecentLogin(
		@RequestParam name: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val returnObj = JsonObject()
		val resolvedToken = resolveLoginToken(token, authorization)
		if (resolvedToken == null) {
			returnObj.addProperty("ok", false)
			returnObj.addProperty("error", "Missing token.")
			return ri.GeneralHttpHeader(returnObj.toString())
		}
		val (username, errorCode) = login.validate(resolvedToken)
		if (username == null || username != name) {
			returnObj.addProperty("ok", false)
			returnObj.addProperty("error", authorityNeededServicesImpl.getErrorMessage(errorCode))
			return ri.GeneralHttpHeader(returnObj.toString())
		}
		val result = authorityNeededServicesImpl.getPlayerLogin(name)
		returnObj.addProperty("ok", result.first)
		if (result.first) {
			returnObj.addProperty("ip", result.second)
		}
		return ri.GeneralHttpHeader(returnObj.toString())
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
				.getPlayerCardListAsJson(username!!)
				.toString()
		)
	}

	@GetMapping("/cards/info")
	suspend fun getCardInfo(@RequestParam id: Long): ResponseEntity<String> {
		val returnObj = JsonObject()
		val result = playerCardCustomizationImpl.getCardInformation(id) ?: return ri.GeneralHttpHeader(
			returnObj.apply {
				addProperty("error", "card not found")
			}.toString()
		)
		return ri.GeneralHttpHeader(result.toString())
	}

	@GetMapping("/cards/all")
	suspend fun getAllCards(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(playerCardCustomizationImpl.getAllCards().convertToJsonArray().toString())
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

fun Boolean.toHumanReadableJson(): String {
	return JsonObject().apply { addProperty("result", this@toHumanReadableJson) }.toString()
}
