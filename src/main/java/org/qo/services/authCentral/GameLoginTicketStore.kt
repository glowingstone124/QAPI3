package org.qo.services.authCentral

import com.google.gson.Gson
import org.qo.redis.Configuration
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class GameLoginTicketRecord(val ticket: String, val username: String, val binding: String,
    val createdAt: Long, val expiresAt: Long)

/** Deliberately disjoint from SsoTicketStore: a pre-authentication challenge must never become an SSO login. */
@Service
class GameLoginTicketStore(private val redis: Redis = Redis()) {
    private val gson = Gson()
    private val random = SecureRandom()
    private val localStore = ConcurrentHashMap<String, GameLoginTicketRecord>()
    private val database = DatabaseType.QO_TEMP_DATABASE.value
    fun createChallenge(username: String, binding: String) = create("GC-", username, binding, 60)
    fun createProof(username: String, binding: String) = create("GP-", username, binding, 30)

    private fun create(prefix: String, username: String, binding: String, seconds: Long): GameLoginTicketRecord {
        cleanup()
        val id = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val now = System.currentTimeMillis()
        val record = GameLoginTicketRecord(id, username, binding, now, now + seconds * 1000)
        if (Configuration.EnableRedis) {
            check(redis.insert(key(id), gson.toJson(record), database, seconds).ignoreException() != null) { "Game ticket storage unavailable" }
        } else localStore[id] = record
        return record
    }

    fun validateAndConsume(ticket: String, binding: String): GameLoginTicketRecord? {
        cleanup()
        val record = if (Configuration.EnableRedis) {
            redis.get(key(ticket), database).ignoreException()?.let { decode(it) }
        } else localStore[ticket]
        if (record == null || record.expiresAt <= System.currentTimeMillis() || record.binding != binding) return null
        val consumed = if (Configuration.EnableRedis)
            redis.getAndDelete(key(ticket), database).ignoreException()?.let { decode(it) }
        else if (localStore.remove(ticket, record)) record else null
        return consumed?.takeIf { it == record && it.expiresAt > System.currentTimeMillis() }
    }

    private fun decode(json: String) = runCatching { gson.fromJson(json, GameLoginTicketRecord::class.java) }.getOrNull()
    private fun key(ticket: String) = "auth:game:v1:$ticket"
    private fun cleanup() { val now = System.currentTimeMillis(); localStore.entries.removeIf { it.value.expiresAt <= now } }
}
