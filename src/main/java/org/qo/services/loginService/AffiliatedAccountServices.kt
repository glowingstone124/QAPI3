package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.orm.AffiliatedAccountORM
import org.qo.orm.UserORM
import org.qo.orm.unsupportedSyncApi
import org.qo.utils.UserProcess
import org.springframework.stereotype.Service
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono

@Service
class AffiliatedAccountServices(private val affiliatedAccountORM: AffiliatedAccountORM, private val login: Login) {
	val gson = Gson()

	data class AffiliatedAccount(val name: String, val host: String, val password: String)
	data class AffiliatedAccountSummary(val name: String, val host: String)

	val userORM = UserORM()

	suspend fun getAffiliatedAccount(token: String): List<AffiliatedAccountSummary> {
		val (username, _) = login.validate(token)
		if (username == null) {
			return emptyList()
		}
		return affiliatedAccountORM.readByHostAsync(username).map {
			AffiliatedAccountSummary(name = it.name, host = it.host)
		}
	}

	fun validateAffiliatedAccount(name: String): Pair<Boolean, AffiliatedAccount?> =
		unsupportedSyncApi("AffiliatedAccountServices.validateAffiliatedAccount")

	suspend fun validateAffiliatedAccountAsync(name: String): Pair<Boolean, AffiliatedAccount?> {
		affiliatedAccountORM.readAsync(name)?.let {
			return Pair(true, it)
		}
		return Pair(false, null)
	}

	fun validateAffiliatedAccountReactive(name: String): Mono<Pair<Boolean, AffiliatedAccount?>> =
		mono { validateAffiliatedAccountAsync(name) }

	suspend fun addAffiliatedAccount(token: String, body: String): Boolean {
		val jsonObj = gson.fromJson(body, JsonObject::class.java)
		val accountName = jsonObj.get("name")?.asString ?: return false
		val password = jsonObj.get("password")?.asString ?: return false

		val (username, _) = login.validate(token)
		if (username == null) {
			return false
		}
		val account = AffiliatedAccount(
			name = accountName,
			host = username,
			password = UserProcess.computePassword(password, true),
		)
		val created = affiliatedAccountORM.createUsingInviteAsync(account)
		if (created) {
			userORM.invalidateByUsername(username)
		}
		return created
	}

	suspend fun removeAffiliatedAccount(token: String, name: String): Boolean {
		val (username, _) = login.validate(token)
		if (username == null) {
			return false
		}
		return affiliatedAccountORM.deleteByNameAndHostAsync(name, username)
	}
}
