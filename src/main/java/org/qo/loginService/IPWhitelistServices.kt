package org.qo.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.qo.orm.SQL
import org.springframework.stereotype.Service
import kotlin.io.use

@Service
class IPWhitelistServices(private val login: Login, private val authorityNeededServices: AuthorityNeededServicesImpl) {
	val gson = Gson()
	suspend fun whitelisted(ip: String): Boolean {
		val conn = SQL.getConnection()
		return conn.createStatement("SELECT username, ip from loginip WHERE ip = ? LIMIT 1")
			.bind(0, ip)
			.execute()
			.flatMap {
				it.map { row, _ -> row }
			}.hasElements().awaitSingle()
	}
	suspend fun whitelistedIpCount(username: String): Int {
		val conn = SQL.getConnection()
		return conn.createStatement("SELECT username, ip from loginip WHERE username = ?")
			.bind(0, username)
			.execute()
			.flatMap {
				it.map { row, _ -> row }
			}.count().awaitSingle().toInt()
	}
	suspend fun addIntoWhitelist(ip: String, username: String) {
		val conn = SQL.getConnection()
		conn.createStatement("INSERT INTO loginip (username,ip) VALUES (?,?)")
			.bind(0, username)
			.bind(1, ip)
			.execute()
	}
	suspend fun joinWhitelist(ip:String, token:String): WhitelistReasons {
		val (username, errorCode) =  login.validate(token)
		if (authorityNeededServices.doPrecheck(username, errorCode) != null || username == null){
			return WhitelistReasons.TOKEN_INVALID
		}
		if (whitelistedIpCount(username) >= 5) {
			return WhitelistReasons.IP_WHITELIST_FULL
		}
		addIntoWhitelist(ip, username)
		return WhitelistReasons.SUCCESS
	}
	//GET a player's whitelisted ip has been implemented in /qo/authority/ip/query?token=
	fun whitelistedWrapper(ip:String): String = runBlocking {
		return@runBlocking JsonObject().apply { addProperty("whitelisted", whitelisted(ip)) }.toString()
	}
	enum class WhitelistReasons {
		SUCCESS,
		TOKEN_INVALID,
		IP_WHITELIST_FULL,
	}
}