package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.datas.Mapping
import org.qo.orm.AffiliatedAccountORM
import org.qo.orm.UserORM
import org.qo.services.gameStatusService.asJsonObject
import org.qo.utils.UserProcess
import org.springframework.stereotype.Service

@Service
class AffiliatedAccountServices(private val affiliatedAccountORM: AffiliatedAccountORM, private val login: Login){
	val gson = Gson()
	data class AffiliatedAccount(val name:String, val host: String, val password: String)
	val userORM = UserORM()
	suspend fun getAffiliatedAccount(token: String):List<AffiliatedAccount> {
		val (username, code) = login.validate(token)
		if (username == null) {
			return emptyList()
		}
		return affiliatedAccountORM.readByHost(username)
	}

	fun validateAffiliatedAccount(name: String): Pair<Boolean, AffiliatedAccount?> {
		affiliatedAccountORM.read(name)?.let {
			return Pair(true,it)
		}
		return Pair(false, null)
	}

	suspend fun addAffiliatedAccount(token: String,body:String): Boolean {
		val jsonObj = gson.fromJson(body, JsonObject::class.java)
		val accountName = jsonObj.get("name")?.asString ?: return false
		val password = jsonObj.get("password")?.asString ?: return false

		val (username, code) = login.validate(token)
		if (username == null) {
			return false
		}
		val hostInfo = userORM.readAsync(username) ?: return false
		val invite = hostInfo.invite ?: return false
		if (invite < 1) {
			return false
		}

		val account = AffiliatedAccount(
			name = accountName,
			host = username,
			password = UserProcess.computePassword(password, true)
		)
		val updatedUser = Mapping.Users(
			username = hostInfo.username,
			uid = hostInfo.uid,
			frozen = hostInfo.frozen,
			remain = hostInfo.remain,
			economy = hostInfo.economy,
			signed = hostInfo.signed,
			playtime = hostInfo.playtime,
			temp = hostInfo.temp,
			invite = (hostInfo.invite ?: 0) - 1,
			password = hostInfo.password,
			profile_id = hostInfo.profile_id,
			exp_level = hostInfo.exp_level,
		)
		userORM.updateAsync(updatedUser)

		return affiliatedAccountORM.create(account) > 0
	}
}
