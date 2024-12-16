package org.qo.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.qo.ReturnInterface
import org.qo.orm.UserORM
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

val userORM = UserORM()
@RestController
@RequestMapping("/qo/authorization")
class AuthorityNeededServicesController(private val login: Login, private val ri: ReturnInterface, private val ans: AuthorityNeededServicesImpl) {
	@GetMapping("/account")
	suspend fun getAccountInfo(@RequestHeader token: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(ans.getAccountInfo(token))
	}

	@GetMapping("/ip/query")
	suspend fun getIpInfo(@RequestHeader token: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(ans.getIpWhitelists(token))
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