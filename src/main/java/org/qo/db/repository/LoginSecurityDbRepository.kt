package org.qo.db.repository

import org.qo.datas.ReactiveDatabase
import org.qo.orm.booleanValue
import org.qo.orm.intValue
import org.qo.orm.longValue
import org.qo.services.loginService.IPWhitelistServices
import org.qo.services.loginService.Login
import org.springframework.stereotype.Repository

@Repository
class LoginSecurityDbRepository(
	private val database: ReactiveDatabase,
) {
	suspend fun insertLoginLog(username: String, time: Long, success: Boolean) {
		database.execute(
			"INSERT INTO login_logs(username, time, success) VALUES (?, ?, ?)",
			listOf(username, time, success),
		)
	}

	suspend fun queryLoginHistory(username: String, limit: Int = 3): List<Login.LoginLog> {
		return database.all(
			"""
			SELECT username, time, success
			FROM login_logs
			WHERE username = ?
			ORDER BY time DESC
			LIMIT ?
			""".trimIndent(),
			listOf(username, limit),
		) { row ->
			Login.LoginLog(
				user = row.get("username", String::class.java).orEmpty(),
				date = longValue(row.get("time")) ?: 0L,
				success = booleanValue(row.get("success")) ?: false,
			)
		}
	}

	suspend fun isIpInLoginIp(ip: String): Boolean =
		database.one("SELECT 1 FROM loginip WHERE ip = ? LIMIT 1", listOf(ip)) { true } != null

	suspend fun isIpInLoginIpForUser(ip: String, username: String): Boolean =
		database.one(
			"SELECT 1 FROM loginip WHERE ip = ? AND username = ? LIMIT 1",
			listOf(ip, username),
		) { true } != null

	suspend fun whitelistedIpCount(username: String): Int =
		database.one(
			"SELECT COUNT(*) AS total FROM loginip WHERE username = ?",
			listOf(username),
		) { row -> intValue(row.get("total")) ?: 0 } ?: 0

	suspend fun addIntoWhitelist(ip: String, username: String) {
		database.execute(
			"INSERT INTO loginip (username, ip) VALUES (?, ?)",
			listOf(username, ip),
		)
	}

	suspend fun removeFromWhitelist(ip: String, username: String): Boolean =
		database.execute(
			"DELETE FROM loginip WHERE username = ? AND ip = ?",
			listOf(username, ip),
		) > 0

	suspend fun getUserIps(username: String): List<String> =
		database.all(
			"SELECT ip FROM loginip WHERE username = ?",
			listOf(username),
		) { row -> row.get("ip", String::class.java) ?: "Unknown IP" }

	suspend fun getLatestLoginIp(username: String): String? =
		database.one(
			"SELECT ip FROM loginip WHERE username = ? LIMIT 1",
			listOf(username),
		) { row -> row.get("ip", String::class.java) }

	suspend fun addWithinLimitInTransaction(
		ip: String,
		username: String,
		maxIps: Int,
	): IPWhitelistServices.WhitelistReasons = database.inTransaction {
		val locked = database.one(
			"SELECT username FROM users WHERE username = ? FOR UPDATE",
			listOf(username),
		) { true } != null
		if (!locked) return@inTransaction IPWhitelistServices.WhitelistReasons.TOKEN_INVALID
		if (isIpInLoginIpForUser(ip, username)) return@inTransaction IPWhitelistServices.WhitelistReasons.SUCCESS
		if (whitelistedIpCount(username) >= maxIps) return@inTransaction IPWhitelistServices.WhitelistReasons.IP_WHITELIST_FULL
		addIntoWhitelist(ip, username)
		IPWhitelistServices.WhitelistReasons.SUCCESS
	}
}
