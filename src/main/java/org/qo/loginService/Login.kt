package org.qo.loginService

import kotlinx.coroutines.runBlocking
import org.qo.orm.LoginToken
import org.qo.orm.LoginTokenORM
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

	suspend fun validate(loginToken: String): String? {
		val result = loginTokenORM.read(loginToken)
		if (result == null) return null
		if (result.expires < System.currentTimeMillis()) {
			loginTokenORM.delete(loginToken)
			return null
		}
		return result.user
	}
}