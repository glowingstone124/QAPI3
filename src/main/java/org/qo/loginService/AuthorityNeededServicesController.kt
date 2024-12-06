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
class AuthorityNeededServicesController(private val login: Login, private val ri: ReturnInterface) {
	@GetMapping("/account")
	suspend fun getAccountInfo(@RequestHeader token: String): ResponseEntity<String> {
		val returnObject = JsonObject()
		val (accountName, errorCode) = login.validate(token)
		if (accountName == null) {
			val errorMessage = when (errorCode) {
				1 -> "Invalid token found."
				3 -> "Token expired."
				else -> "Unknown error."
			}
			returnObject.addProperty("error", errorCode)
			returnObject.addProperty("message", errorMessage)
			return ri.GeneralHttpHeader(returnObject.toString())
		}
		val userInfo = userORM.read(accountName)
		if (userInfo == null) {
			returnObject.addProperty("error", 200)
			returnObject.addProperty("message", "User not found.")
		}
		//Sheet 1
		returnObject.addProperty("username", accountName)
		returnObject.addProperty("uid", userInfo!!.uid)
		returnObject.addProperty("playtime", userInfo.playtime)

		//Phrase 2
		val result = login.queryLoginHistory(username = accountName).convertToJsonArray()
		returnObject.add("logins", result)
		return ri.GeneralHttpHeader(returnObject.toString())
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