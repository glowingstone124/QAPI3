package org.qo.services.authCentral

import com.google.gson.Gson
import org.qo.redis.Configuration
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class SsoTicketRecord(
	val ticket: String,
	val username: String,
	val service: String,
	val createdAt: Long,
	val expiresAt: Long
)

@Service
class SsoTicketStore(
	private val redis: Redis = Redis(),
	private val database: Int = DatabaseType.QO_TEMP_DATABASE.value
) {
	private val gson = Gson()
	private val localStore = ConcurrentHashMap<String, SsoTicketRecord>()

	companion object {
		const val TICKET_PREFIX = "ST-"
		const val TICKET_TTL_SECONDS = 180L // 3 minutes
		private const val TICKET_TTL_MILLIS = TICKET_TTL_SECONDS * 1000L
		private val secureRandom = SecureRandom()
	}

	@OptIn(ExperimentalEncodingApi::class)
	fun generateTicketId(): String {
		val bytes = ByteArray(32)
		secureRandom.nextBytes(bytes)
		return TICKET_PREFIX + Base64.UrlSafe.encode(bytes).trimEnd('=')
	}

	fun createTicket(username: String, service: String): SsoTicketRecord {
		cleanupExpired()
		val now = System.currentTimeMillis()
		val ticket = generateTicketId()
		val record = SsoTicketRecord(
			ticket = ticket,
			username = username,
			service = service.trim(),
			createdAt = now,
			expiresAt = now + TICKET_TTL_MILLIS
		)

		localStore[ticket] = record

		if (Configuration.EnableRedis) {
			val key = redisKey(ticket)
			val json = gson.toJson(record)
			redis.insert(key, json, database, TICKET_TTL_SECONDS)
				.onException { println("Failed to store SSO ticket in Redis: ${it.message}") }
		}

		return record
	}

	fun validateAndConsume(ticket: String, service: String): SsoTicketRecord? {
		cleanupExpired()
		val cleanTicket = ticket.trim()
		val key = redisKey(cleanTicket)

		val existingRecord = if (Configuration.EnableRedis) {
			val json = redis.get(key, database).onException {
				println("Failed to read SSO ticket from Redis: ${it.message}")
			} ?: return null
			runCatching { gson.fromJson(json, SsoTicketRecord::class.java) }.getOrNull()
		} else {
			localStore[cleanTicket]
		} ?: return null

		if (existingRecord.expiresAt <= System.currentTimeMillis()) {
			if (Configuration.EnableRedis) redis.delete(key, database).ignoreException()
			localStore.remove(cleanTicket)
			return null
		}

		if (!serviceMatches(existingRecord.service, service.trim())) {
			return null
		}

		if (Configuration.EnableRedis) {
			val deletedJson = redis.getAndDelete(key, database).onException {
				println("Failed to delete SSO ticket from Redis: ${it.message}")
			}
			localStore.remove(cleanTicket)
			if (deletedJson == null) return null
		} else {
			if (localStore.remove(cleanTicket) == null) return null
		}

		return existingRecord
	}

	private fun serviceMatches(registeredService: String, requestedService: String): Boolean {
		if (registeredService == requestedService) return true
		if (registeredService.trimEnd('/') == requestedService.trimEnd('/')) return true
		return false
	}

	private fun cleanupExpired() {
		val now = System.currentTimeMillis()
		localStore.entries.removeIf { (_, record) -> record.expiresAt <= now }
	}

	private fun redisKey(ticket: String) = "auth:st:$ticket"
}
