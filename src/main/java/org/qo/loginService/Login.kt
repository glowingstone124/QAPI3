package org.qo.loginService

import com.google.gson.Gson
import com.google.gson.JsonArray
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.qo.orm.LoginToken
import org.qo.orm.LoginTokenORM
import org.qo.orm.SQL
import org.springframework.stereotype.Service
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.use

@Service
class Login {
	val loginTokenORM: LoginTokenORM = LoginTokenORM()

	@OptIn(ExperimentalEncodingApi::class)
	fun generateToken(length: Int = 64): String {
		val secureRandom = SecureRandom()
		val bytes = ByteArray(length)
		secureRandom.nextBytes(bytes)
		return Base64.encode(bytes)
	}

	fun insertInto(loginToken: String, user: String) = runBlocking {
		loginTokenORM.create(
			LoginToken (
				loginToken,
				user,
				System.currentTimeMillis() + 604800000,
			)
		)
	}

	suspend fun validate(loginToken: String): Pair<String?,Int> {
		val result = loginTokenORM.read(loginToken)
		if (result == null) return Pair(null,1)
		if (result.expires < System.currentTimeMillis()) {
			loginTokenORM.delete(loginToken)
			return Pair(null,3)
		}
		return Pair(result.user,0)
	}

	fun insertLoginLog(data: String) = runBlocking {
		val connection = SQL.getConnection()
		val jsonObj = Gson().fromJson(data, LoginLog::class.java)
		connection.createStatement("INSERT INTO login_logs(username, time, success) VALUES (?, ?, ?)")
			.bind(0, jsonObj.user)
			.bind(1, jsonObj.date)
			.bind(2, jsonObj.success)
		.execute().awaitSingle()
	}

	fun queryLoginHistory(username: String): List<LoginLog> = runBlocking {
		val connection = SQL.getConnection()

		connection.createStatement(
			"""
        SELECT username, time, success FROM login_logs 
        WHERE username = ? 
        ORDER BY time DESC 
        LIMIT 3
        """
		)
			.bind(0, username)
			.execute()
			.flatMap { result ->
				result.map { row, _ ->
					LoginLog(
						user = row.get("username", String::class.java) ?: "",
						date = row.get("time", Long::class.java) ?: 0L,
						success = row.get("success", Boolean::class.java) ?: false
					)
				}
			}
			.collectList()
			.awaitFirst()
	}


	data class LoginLog(
		val user: String,
		val date: Long,
		val success: Boolean
	)

}