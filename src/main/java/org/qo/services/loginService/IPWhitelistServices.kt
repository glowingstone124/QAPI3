package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.qo.datas.ConnectionPool
import org.qo.orm.SQL
import org.springframework.stereotype.Service

@Service
class IPWhitelistServices(private val login: Login, private val authorityNeededServices: AuthorityNeededServicesImpl) {
	val gson = Gson()
	fun whitelisted(ip: String): Boolean {
		ConnectionPool.getConnection().use { conn ->
			val sql = "SELECT username, ip FROM loginip WHERE ip = ? LIMIT 1"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, ip)
				stmt.executeQuery().use { rs ->
					return rs.next()
				}
			}
		}
	}
	fun whitelistedIpCount(username: String): Int {
		ConnectionPool.getConnection().use { conn ->
			val sql = "SELECT username, ip FROM loginip WHERE username = ?"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				stmt.executeQuery().use { rs ->
					var count = 0
					while (rs.next()) count++
					return count
				}
			}
		}
	}

	fun addIntoWhitelist(ip: String, username: String) {
		ConnectionPool.getConnection().use { conn ->
			val sql = "INSERT INTO loginip (username, ip) VALUES (?, ?)"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				stmt.setString(2, ip)
				stmt.executeUpdate()
			}
		}
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