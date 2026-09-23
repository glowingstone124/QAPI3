package org.qo.services.loginService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.reactor.mono
import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.LoginSecurityDbRepository
import org.qo.orm.unsupportedSyncApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.net.Inet6Address
import java.net.InetAddress

@Service
class IPWhitelistServices {
	private val login: Login
	private val authorityNeededServices: AuthorityNeededServicesImpl
	private val repository: LoginSecurityDbRepository

	@Autowired
	constructor(
		login: Login,
		authorityNeededServices: AuthorityNeededServicesImpl,
		database: ReactiveDatabase,
		@Autowired(required = false) repository: LoginSecurityDbRepository? = null,
	) {
		this.login = login
		this.authorityNeededServices = authorityNeededServices
		this.repository = repository ?: LoginSecurityDbRepository(database)
	}

	constructor(
		login: Login,
		authorityNeededServices: AuthorityNeededServicesImpl,
		repository: LoginSecurityDbRepository,
	) {
		this.login = login
		this.authorityNeededServices = authorityNeededServices
		this.repository = repository
	}

	constructor(login: Login, authorityNeededServices: AuthorityNeededServicesImpl) : this(
		login,
		authorityNeededServices,
		resolveRepository(),
	)

	val gson = Gson()

	fun whitelisted(ip: String): Boolean = unsupportedSyncApi("IPWhitelistServices.whitelisted")

	suspend fun whitelistedAsync(ip: String): Boolean {
		val normalizedIp = normalizeIp(ip) ?: return false
		if (repository.isIpInLoginIp(normalizedIp)) {
			return true
		}
		return normalizedIp != ip && repository.isIpInLoginIp(ip)
	}

	fun whitelisted(ip: String, username: String): Boolean = unsupportedSyncApi("IPWhitelistServices.whitelistedForUser")

	suspend fun whitelistedAsync(ip: String, username: String): Boolean {
		val normalizedIp = normalizeIp(ip) ?: return false
		if (whitelistedForUser(normalizedIp, username)) return true
		return normalizedIp != ip && whitelistedForUser(ip, username)
	}

	fun whitelistedIpCount(username: String): Int = unsupportedSyncApi("IPWhitelistServices.whitelistedIpCount")

	suspend fun whitelistedIpCountAsync(username: String): Int = repository.whitelistedIpCount(username)

	fun addIntoWhitelist(ip: String, username: String): Unit = unsupportedSyncApi("IPWhitelistServices.addIntoWhitelist")

	suspend fun addIntoWhitelistAsync(ip: String, username: String) {
		repository.addIntoWhitelist(ip, username)
	}

	fun removeFromWhitelist(ip: String, username: String): Boolean =
		unsupportedSyncApi("IPWhitelistServices.removeFromWhitelist")

	suspend fun removeFromWhitelistAsync(ip: String, username: String): Boolean =
		repository.removeFromWhitelist(ip, username)

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

	suspend fun getWhitelistedIpsAsync(username: String): List<String> = repository.getUserIps(username)

	private suspend fun addWithinLimit(ip: String, username: String): WhitelistReasons =
		repository.addWithinLimitInTransaction(ip, username, MAX_IPS_PER_USER)

	private suspend fun whitelistedForUser(ip: String, username: String): Boolean =
		repository.isIpInLoginIpForUser(ip, username)

	fun getWhitelistedIps(username: String): List<String> = unsupportedSyncApi("IPWhitelistServices.getWhitelistedIps")

	fun whitelistedIpCountReactive(username: String): Mono<Int> = mono { whitelistedIpCountAsync(username) }

	fun joinWhitelistReactive(ip: String, token: String): Mono<WhitelistReasons> = mono { joinWhitelist(ip, token) }

	fun leaveWhitelistReactive(ip: String, token: String): Mono<WhitelistReasons> = mono { leaveWhitelist(ip, token) }

	fun getWhitelistedIpsReactive(username: String): Mono<String> = mono {
		val ips = getWhitelistedIpsAsync(username)
		val jsonObject = JsonObject().apply {
			add("ips", gson.toJsonTree(ips))
		}
		jsonObject.toString()
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

		private fun resolveRepository(): LoginSecurityDbRepository =
			runCatching { org.qo.utils.SpringContextUtil.ctx.getBean(LoginSecurityDbRepository::class.java) }.getOrElse {
				LoginSecurityDbRepository(org.qo.orm.reactiveDatabase(null))
			}
	}
}
