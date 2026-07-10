package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Service
import java.net.Inet6Address
import java.net.InetAddress
import java.sql.Connection

@Service
class IPWhitelistServices(private val login: Login, private val authorityNeededServices: AuthorityNeededServicesImpl) {
	val gson = Gson()
	fun whitelisted(ip: String): Boolean {
		val normalizedIp = normalizeIp(ip) ?: return false
		ConnectionPool.getConnection().use { conn ->
			val sql = "SELECT username, ip FROM loginip WHERE ip = ? LIMIT 1"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, normalizedIp)
				stmt.executeQuery().use { rs ->
					if (rs.next()) return true
				}
			}
			if (normalizedIp != ip) {
				conn.prepareStatement(sql).use { stmt ->
					stmt.setString(1, ip)
					stmt.executeQuery().use { rs -> return rs.next() }
				}
			}
			return false
		}
	}

	fun whitelisted(ip: String, username: String): Boolean {
		val normalizedIp = normalizeIp(ip) ?: return false
		ConnectionPool.getConnection().use { conn ->
			if (whitelisted(conn, normalizedIp, username)) return true
			return normalizedIp != ip && whitelisted(conn, ip, username)
		}
	}

	fun whitelistedIpCount(username: String): Int {
		ConnectionPool.getConnection().use { conn ->
			val sql = "SELECT COUNT(*) AS total FROM loginip WHERE username = ?"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				stmt.executeQuery().use { rs ->
					return if (rs.next()) rs.getInt("total") else 0
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

	fun removeFromWhitelist(ip: String, username: String): Boolean {
		ConnectionPool.getConnection().use { conn ->
			val sql = "DELETE FROM loginip WHERE username = ? AND ip = ?"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, username)
				stmt.setString(2, ip)
				return stmt.executeUpdate() > 0
			}
		}
	}

	suspend fun joinWhitelist(ip:String, token:String): WhitelistReasons {
		val normalizedIp = normalizeIp(ip) ?: return WhitelistReasons.INVALID_IP
		val (username, errorCode) =  login.validate(token)
		if (authorityNeededServices.doPrecheck(username, errorCode) != null || username == null){
			return WhitelistReasons.TOKEN_INVALID
		}
		return addWithinLimit(normalizedIp, username)
	}

	suspend fun leaveWhitelist(ip: String, token: String): WhitelistReasons {
		val normalizedIp = normalizeIp(ip) ?: return WhitelistReasons.INVALID_IP
		val (username, errorCode) = login.validate(token)
		if (authorityNeededServices.doPrecheck(username, errorCode) != null || username == null) {
			return WhitelistReasons.TOKEN_INVALID
		}
		val removed = removeFromWhitelist(normalizedIp, username) ||
			(normalizedIp != ip && removeFromWhitelist(ip, username))
		if (!removed) {
			return WhitelistReasons.IP_NOT_FOUND
		}
		return WhitelistReasons.SUCCESS
	}

	private fun addWithinLimit(ip: String, username: String): WhitelistReasons {
		ConnectionPool.getConnection().use { conn ->
			val sqlite = conn.metaData.databaseProductName.equals("SQLite", ignoreCase = true)
			if (sqlite) {
				conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
				conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
			} else {
				conn.autoCommit = false
			}

			try {
				if (!sqlite) lockUserRow(conn, username)
				val result = when {
					whitelisted(conn, ip, username) -> WhitelistReasons.SUCCESS
					whitelistedIpCount(conn, username) >= MAX_IPS_PER_USER -> WhitelistReasons.IP_WHITELIST_FULL
					else -> {
						addIntoWhitelist(conn, ip, username)
						WhitelistReasons.SUCCESS
					}
				}
				if (sqlite) {
					conn.createStatement().use { it.execute("COMMIT") }
				} else {
					conn.commit()
				}
				return result
			} catch (exception: Exception) {
				if (sqlite) {
					runCatching { conn.createStatement().use { it.execute("ROLLBACK") } }
				} else {
					runCatching { conn.rollback() }
				}
				throw exception
			}
		}
	}

	private fun lockUserRow(conn: Connection, username: String) {
		conn.prepareStatement("SELECT username FROM users WHERE username = ? FOR UPDATE").use { stmt ->
			stmt.setString(1, username)
			stmt.executeQuery().use { it.next() }
		}
	}

	private fun whitelisted(conn: Connection, ip: String, username: String): Boolean {
		conn.prepareStatement("SELECT 1 FROM loginip WHERE username = ? AND ip = ? LIMIT 1").use { stmt ->
			stmt.setString(1, username)
			stmt.setString(2, ip)
			stmt.executeQuery().use { return it.next() }
		}
	}

	private fun whitelistedIpCount(conn: Connection, username: String): Int {
		conn.prepareStatement("SELECT COUNT(*) AS total FROM loginip WHERE username = ?").use { stmt ->
			stmt.setString(1, username)
			stmt.executeQuery().use { return if (it.next()) it.getInt("total") else 0 }
		}
	}

	private fun addIntoWhitelist(conn: Connection, ip: String, username: String) {
		conn.prepareStatement("INSERT INTO loginip (username, ip) VALUES (?, ?)").use { stmt ->
			stmt.setString(1, username)
			stmt.setString(2, ip)
			stmt.executeUpdate()
		}
	}

	fun normalizeIp(ip: String): String? {
		if (ip.isEmpty() || ip.length > MAX_IP_LENGTH || ip != ip.trim() || '%' in ip) return null
		if (':' in ip) {
			if (!ip.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return null
			return runCatching { InetAddress.getByName(ip) }
				.getOrNull()
				?.takeIf { it is Inet6Address }
				?.hostAddress
				?.lowercase()
		}

		val parts = ip.split('.')
		if (parts.size != 4) return null
		val octets = parts.map { part ->
			if (part.isEmpty() || !part.all(Char::isDigit) || (part.length > 1 && part.startsWith('0'))) return null
			part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
		}
		return octets.joinToString(".")
	}

	//GET a player's whitelisted ip has been implemented in /qo/authority/ip/query?token=
	fun whitelistedWrapper(ip:String): String = runBlocking {
		return@runBlocking JsonObject().apply { addProperty("whitelisted", whitelisted(ip)) }.toString()
	}
	enum class WhitelistReasons {
		SUCCESS,
		TOKEN_INVALID,
		IP_WHITELIST_FULL,
		IP_NOT_FOUND,
		INVALID_IP,
	}

	companion object {
		private const val MAX_IPS_PER_USER = 5
		private const val MAX_IP_LENGTH = 45
	}
}
