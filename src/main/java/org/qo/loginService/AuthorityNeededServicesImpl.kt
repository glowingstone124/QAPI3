package org.qo.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.reactive.awaitSingle
import org.qo.Msg
import org.qo.ReturnInterface
import org.qo.orm.SQL
import org.springframework.stereotype.Service
import org.sqlite.SQLiteErrorCode.getErrorCode

@Service
class AuthorityNeededServicesImpl(private val login: Login, private val ri: ReturnInterface) {
	val gson = Gson()

	data class WebChatWrapper(
		val message: String,
		val timestamp: Long,
	)

	suspend fun insertWebMessage(msg: String, token: String): Pair<Int, String> {
		val (username, errCode) = login.validate(token)
		doPrecheck(username, errCode)?.let {
			return Pair(1, getErrorMessage(1));
		}
		val resultJson = runCatching {
			gson.fromJson(msg, WebChatWrapper::class.java)
		}.onFailure {
			return Pair(20, getErrorMessage(20))
		}
		Msg.putWebchat(resultJson.getOrNull()?.message ?: "",username!!)
		return Pair(0, "ok")
	}

	suspend fun getAccountInfo(token: String): String {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return precheckResult
		}
		val userInfo = userORM.read(accountName!!)
		val returnObject = JsonObject().apply {
			addProperty("username", accountName)
			addProperty("uid", userInfo!!.uid)
			addProperty("playtime", userInfo.playtime)
		}
		val loginHistory = login.queryLoginHistory(username = accountName).convertToJsonArray()
		returnObject.add("logins", loginHistory)
		return returnObject.toString()
	}

	suspend fun getIpWhitelists(token: String): String {
		val (accountName, errorCode) = login.validate(token)
		val precheckResult = doPrecheck(accountName, errorCode)
		if (precheckResult != null) {
			return precheckResult
		}
		val connection = SQL.getConnection()
		val ipCountResult = connection.createStatement("SELECT * FROM loginip WHERE username = ?")
			.bind(0, accountName!!)
			.execute()
			.awaitSingle().map { row, _ ->
				return@map row.get("ip", String::class.java) ?: "Unknown IP"
			}.collectList().awaitSingle().convertToJsonArray()

		return ipCountResult.toString()
	}

	suspend fun doPrecheck(accountName: String?, errorCode: Int): String? {
		if (accountName == null) {
			val errorMessage = getErrorMessage(errorCode)
			val returnObject = JsonObject().apply {
				addProperty("error", errorCode)
				addProperty("message", errorMessage)
			}
			return returnObject.toString()
		}

		val userInfo = userORM.read(accountName)
		if (userInfo == null) {
			val returnObject = JsonObject().apply {
				addProperty("error", 200)
				addProperty("message", "User not found.")
			}
			return returnObject.toString()
		}

		return null
	}

	fun getErrorMessage(errorCode: Int): String {
		return when (errorCode) {
			1 -> "Invalid token found."
			3 -> "Token expired."
			20 -> "Format error."
			else -> "Unknown error."
		}
	}
}