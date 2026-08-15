package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.datas.ReactiveDatabase
import org.qo.orm.reactiveDatabase
import org.qo.orm.unsupportedSyncApi
import org.springframework.stereotype.Service
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono

@Service
class IPWhitelistServices(private val login: Login, private val authorityNeededServices: AuthorityNeededServicesImpl) {
	private var databaseOverride: ReactiveDatabase? = null
	val gson = Gson()

	private val database: ReactiveDatabase
		get() = reactiveDatabase(databaseOverride)

	fun whitelisted(ip: String): Boolean = unsupportedSyncApi("IPWhitelistServices.whitelisted")

	suspend fun whitelistedAsync(ip: String): Boolean {
		val normalizedIp = normalizeIp(ip) ?: return false
		if (database.one("SELECT 1 FROM loginip WHERE ip = ? LIMIT 1", listOf(normalizedIp)) { true } != null) {
			return true
		}
		return normalizedIp != ip &&
			database.one("SELECT 1 FROM loginip WHERE ip = ? LIMIT 1", listOf(ip)) { true } != null
	}

	fun whitelisted(ip: String, username: String): Boolean = unsupportedSyncApi("IPWhitelistServices.whitelistedForUser")

	suspend fun whitelistedAsync(ip: String, username: String): Boolean {
		val normalizedIp = normalizeIp(ip) ?: return false
		if (whitelistedForUser(normalizedIp, username)) return true
		return normalizedIp != ip && whitelistedForUser(ip, username)
	}

	fun whitelistedIpCount(username: String): Int = unsupportedSyncApi("IPWhitelistServices.whitelistedIpCount")

	suspend fun whitelistedIpCountAsync(username: String): Int = database.one(
		"SELECT COUNT(*) AS total FROM loginip WHERE username = ?",
		listOf(username),
	) { row -> org.qo.orm.intValue(row.get("total")) ?: 0 } ?: 0

	fun addIntoWhitelist(ip: String, username: String): Unit = unsupportedSyncApi("IPWhitelistServices.addIntoWhitelist")

	suspend fun addIntoWhitelistAsync(ip: String, username: String) {
		database.execute(
			"INSERT INTO loginip (username, ip) VALUES (?, ?)",
			listOf(username, ip),
		)
	}

	fun removeFromWhitelist(ip: String, username: String): Boolean =
		unsupportedSyncApi("IPWhitelistServices.removeFromWhitelist")

	suspend fun removeFromWhitelistAsync(ip: String, username: String): Boolean =
		database.execute(
			"DELETE FROM loginip WHERE username = ? AND ip = ?",
			listOf(username, ip),
		) > 0

	suspend fun joinWhitelist(ip: String, token: String): WhitelistReasons {
		val normalizedIp = normalizeIp(ip) ?: return WhitelistReasons.INVALID_IP
		val (username, errorCode) = login.validate(token)
		if (authorityNeededServices.doPrecheck(username, errorCode) != null || username == null) {
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
		val removed = removeFromWhitelistAsync(normalizedIp, username) ||
			(normalizedIp != ip && removeFromWhitelistAsync(ip, username))
		if (!removed) {
			return WhitelistReasons.IP_NOT_FOUND
		}
		return WhitelistReasons.SUCCESS
	}

	suspend fun getWhitelistedIpsAsync(username: String): List<String> = database.all(
		"SELECT ip FROM loginip WHERE username = ?",
		listOf(username),
	) { row -> row.get("ip", String::class.java).orEmpty() }

	private suspend fun addWithinLimit(ip: String, username: String): WhitelistReasons =
		database.inTransaction {
			val locked = database.one(
				"SELECT username FROM users WHERE username = ? FOR UPDATE",
				listOf(username),
			) { true } != null
			if (!locked) return@inTransaction WhitelistReasons.TOKEN_INVALID
			when {
				whitelistedForUser(ip, username) -> WhitelistReasons.SUCCESS
				whitelistedIpCountAsync(username) >= MAX_IPS_PER_USER -> WhitelistReasons.IP_WHITELIST_FULL
				else -> {
					addIntoWhitelistAsync(ip, username)
					WhitelistReasons.SUCCESS
				}
			}
		}

	private suspend fun whitelistedForUser(ip: String, username: String): Boolean =
		database.one(
			"SELECT 1 FROM loginip WHERE username = ? AND ip = ? LIMIT 1",
			listOf(username, ip),
		) { true } != null

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

	fun whitelistedWrapper(ip: String): String = unsupportedSyncApi("IPWhitelistServices.whitelistedWrapper")

	fun whitelistedWrapperReactive(ip: String): Mono<String> = mono {
		JsonObject().apply {
			addProperty("whitelisted", whitelistedAsync(ip))
		}.toString()
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
