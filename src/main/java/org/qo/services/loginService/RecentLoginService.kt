package org.qo.services.loginService

import com.google.gson.Gson
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

interface RecentLoginStore {
	fun put(key: String, value: String, expiresSeconds: Long): Boolean
	fun get(key: String): String?
}

@Component
class RedisRecentLoginStore : RecentLoginStore {
	private val redis = Redis()

	override fun put(key: String, value: String, expiresSeconds: Long): Boolean =
		redis.insert(key, value, DatabaseType.QO_TEMP_DATABASE.value, expiresSeconds).ignoreException() != null

	override fun get(key: String): String? =
		redis.get(key, DatabaseType.QO_TEMP_DATABASE.value).ignoreException()
}

@Service
class RecentLoginService(private val store: RecentLoginStore) {
	private val gson = Gson()

	@JvmOverloads
	fun recordSuccessfulLogin(
		username: String,
		ip: String?,
		nowMillis: Long = System.currentTimeMillis()
	): Boolean {
		val key = keyFor(username) ?: return false
		val normalizedIp = normalizeIp(ip) ?: return false
		val record = RecentLoginRecord(normalizedIp, nowMillis)
		return store.put(key, gson.toJson(record), WINDOW_SECONDS)
	}

	fun canAutoLogin(
		username: String?,
		ip: String?
	): Boolean = canAutoLogin(username, ip, System.currentTimeMillis())

	fun canAutoLogin(
		username: String?,
		ip: String?,
		nowMillis: Long
	): Boolean {
		val key = keyFor(username) ?: return false
		val normalizedIp = normalizeIp(ip) ?: return false
		val rawRecord = store.get(key) ?: return false
		val record = runCatching {
			gson.fromJson(rawRecord, RecentLoginRecord::class.java)
		}.getOrNull() ?: return false

		val ageMillis = nowMillis - record.loggedAt
		if (ageMillis !in 0 until WINDOW_MILLIS) return false

		val expected = normalizeIp(record.ip) ?: return false
		return MessageDigest.isEqual(
			expected.toByteArray(StandardCharsets.UTF_8),
			normalizedIp.toByteArray(StandardCharsets.UTF_8)
		)
	}

	private fun keyFor(username: String?): String? {
		val normalized = username?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_USERNAME_LENGTH }
			?: return null
		return KEY_PREFIX + normalized.lowercase(Locale.ROOT)
	}

	private fun normalizeIp(ip: String?): String? {
		val value = ip ?: return null
		if (value.isEmpty() || value.length > MAX_IP_LENGTH || value != value.trim() || '%' in value) return null

		if (':' !in value) {
			val parts = value.split('.')
			if (parts.size != 4 || parts.any { part ->
				part.isEmpty() || !part.all(Char::isDigit) || part.toIntOrNull() !in 0..255
			}) return null
			return parts.joinToString(".") { it.toInt().toString() }
		}

		if (!value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return null
		return runCatching { InetAddress.getByName(value).hostAddress.lowercase(Locale.ROOT) }.getOrNull()
	}

	private data class RecentLoginRecord(
		val ip: String,
		val loggedAt: Long
	)

	companion object {
		const val WINDOW_SECONDS = 60L
		const val WINDOW_MILLIS = WINDOW_SECONDS * 1_000L
		private const val KEY_PREFIX = "recent_game_login:"
		private const val MAX_USERNAME_LENGTH = 64
		private const val MAX_IP_LENGTH = 45
	}
}
