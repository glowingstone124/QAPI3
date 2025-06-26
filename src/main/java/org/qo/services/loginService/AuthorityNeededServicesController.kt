package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.asyncer.r2dbc.mysql.message.server.DecodeContext.result
import org.qo.utils.ReturnInterface
import org.qo.services.loginService.IPWhitelistServices.WhitelistReasons
import org.qo.orm.UserORM
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
class AuthorityNeededServicesController(private val login: Login, private val ri: ReturnInterface, private val ipWhitelistServices: IPWhitelistServices, private val authorityNeededServicesImpl: AuthorityNeededServicesImpl, private val playerCardCustomizationImpl: PlayerCardCustomizationImpl) {

	@PostMapping("/message/upload")
	suspend fun insertWebMessage(@RequestBody msg: String, @RequestHeader token:String): ResponseEntity<String> {
		val (code, result)=authorityNeededServicesImpl.insertWebMessage(msg, token)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", code)
			addProperty("result",result)
		}.toString())
	}

	@GetMapping("/account")
	suspend fun getAccountInfo(@RequestHeader token: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(authorityNeededServicesImpl.getAccountInfo(token))
	}

	@GetMapping("/account/card")
	fun getAccountCardInfo(@RequestParam profileUuid: String): ResponseEntity<String> {
		val result = playerCardCustomizationImpl.getProfileDetail(profileUuid)
		if (result == null) {
			return ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("error", "no profile found")
				addProperty("code","1")
			}.toString())
		}
		val jsonObj = JsonParser.parseString(result).asJsonObject.apply{addProperty("code", 0)}
		return ri.GeneralHttpHeader(jsonObj.toString())
	}

	@GetMapping("/ip/query")
	suspend fun getIpInfo(@RequestHeader token: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(authorityNeededServicesImpl.getIpWhitelists(token))
	}

	@GetMapping("/ip/add")
	suspend fun insertIntoIpWhitelist(@RequestHeader token: String, @RequestParam ip: String): ResponseEntity<String> {
		val (username, errorCode) =  login.validate(token)
		if (authorityNeededServicesImpl.doPrecheck(username, errorCode) != null || username == null){
			return ri.GeneralHttpHeader(Return(1, authorityNeededServicesImpl.getErrorMessage(1)).serialized())
		}
		return when (ipWhitelistServices.joinWhitelist(ip, token)) {
			WhitelistReasons.SUCCESS -> ri.GeneralHttpHeader(Return(0, "ok").serialized())
			WhitelistReasons.TOKEN_INVALID -> ri.GeneralHttpHeader(Return(1, authorityNeededServicesImpl.getErrorMessage(1) + "(else)").serialized())
			WhitelistReasons.IP_WHITELIST_FULL -> ri.GeneralHttpHeader(Return(2, "Too many ips").serialized())
		}
	}

	@GetMapping("/fortune")
	suspend fun getFortuneForUser(@RequestHeader token: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(authorityNeededServicesImpl.calculateFortune(token))
	}

	@GetMapping("/templogin")
	suspend fun getPlayerRecentLogin(@RequestParam name: String): ResponseEntity<String> {
		val returnObj = JsonObject()
		val result = authorityNeededServicesImpl.getPlayerLogin(name)
		returnObj.addProperty("ok", result.first)
		if (result.first){
			returnObj.addProperty("ip", result.second)
		}
		return ReturnInterface().GeneralHttpHeader(returnObj.toString())
	}

	@GetMapping("/cards/obtained")
	suspend fun getPlayerCardList(@RequestHeader token: String): ResponseEntity<String> {
		val returnObj = JsonObject()
		val (username, errorCode) =  login.validate(token)
		if (authorityNeededServicesImpl.doPrecheck(username, errorCode) != null){
			return ri.GeneralHttpHeader(returnObj.apply {
				addProperty("error", "invalid username")
			}.toString())
		}
		return ReturnInterface().GeneralHttpHeader(playerCardCustomizationImpl
			.getPlayerCardList(username!!)
			.convertToJsonArray()
			.toString()
		)
	}
	@GetMapping("/cards/info")
	suspend fun getCardInfo(@RequestParam id: Long): ResponseEntity<String> {
		val returnObj = JsonObject()
		val result = playerCardCustomizationImpl.getCardInformation(id)
		if (result == null){
			return ri.GeneralHttpHeader(
				returnObj.apply {
					addProperty("error", "card not found")
				}.toString()
			)
		}
		return ri.GeneralHttpHeader(result.toString())
	}
	@GetMapping("/cards/all")
	suspend fun getAllCards():ResponseEntity<String> {
		return ri.GeneralHttpHeader(playerCardCustomizationImpl.getAllCards().convertToJsonArray().toString())
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
fun <T> List<T>.convertToJsonArray(): JsonArray {
	val gson = Gson()
	val jsonArray = JsonArray()

	this.forEach { element ->
		val jsonElement = gson.toJsonTree(element)
		jsonArray.add(jsonElement)
	}

	return jsonArray
}