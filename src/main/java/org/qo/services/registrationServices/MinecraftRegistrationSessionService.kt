package org.qo.services.registrationServices

import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class MinecraftRegistrationSessionState {
	PENDING,
	CLAIMED,
	COMPLETED
}

data class MinecraftRegistrationSession(
	val id: String,
	val name: String,
	val uid: Long,
	val expiresAt: Long,
	val state: MinecraftRegistrationSessionState,
	val claimedByNodeId: Int? = null,
	val passed: Boolean? = null
)

@Service
class MinecraftRegistrationSessionService {
	private val sessionsById = ConcurrentHashMap<String, MinecraftRegistrationSession>()
	private val sessionIdByName = ConcurrentHashMap<String, String>()

	@Synchronized
	fun create(name: String, uid: Long): MinecraftRegistrationSession {
		cleanup()
		val normalizedName = normalize(name)
		sessionIdByName.remove(normalizedName)?.let(sessionsById::remove)
		sessionsById.entries.removeIf { it.value.uid == uid }
		val session = MinecraftRegistrationSession(
			id = UUID.randomUUID().toString(),
			name = name,
			uid = uid,
			expiresAt = System.currentTimeMillis() + SESSION_TTL_MILLIS,
			state = MinecraftRegistrationSessionState.PENDING
		)
		sessionsById[session.id] = session
		sessionIdByName[normalizedName] = session.id
		return session
	}

	@Synchronized
	fun claim(name: String, nodeId: Int): MinecraftRegistrationSession? {
		cleanup()
		val id = sessionIdByName[normalize(name)] ?: return null
		val current = sessionsById[id] ?: return null
		val claimed = when (current.state) {
			MinecraftRegistrationSessionState.PENDING -> current.copy(
				state = MinecraftRegistrationSessionState.CLAIMED,
				claimedByNodeId = nodeId,
				expiresAt = System.currentTimeMillis() + CLAIMED_SESSION_TTL_MILLIS
			)
			MinecraftRegistrationSessionState.CLAIMED -> {
				if (current.claimedByNodeId != nodeId) return null
				current.copy(
					expiresAt = System.currentTimeMillis() + CLAIMED_SESSION_TTL_MILLIS
				)
			}
			MinecraftRegistrationSessionState.COMPLETED -> return null
		}
		sessionsById[id] = claimed
		return claimed
	}

	@Synchronized
	fun complete(sessionId: String, name: String, nodeId: Int, passed: Boolean): MinecraftRegistrationSession? {
		cleanup()
		val current = sessionsById[sessionId] ?: return null
		if (current.state == MinecraftRegistrationSessionState.COMPLETED) {
			return current.takeIf {
				it.claimedByNodeId == nodeId &&
					it.name.equals(name, ignoreCase = true) &&
					it.passed == passed
			}
		}
		if (current.state != MinecraftRegistrationSessionState.CLAIMED
			|| current.claimedByNodeId != nodeId
			|| !current.name.equals(name, ignoreCase = true)
		) return null
		val completed = current.copy(
			state = MinecraftRegistrationSessionState.COMPLETED,
			passed = passed,
			expiresAt = System.currentTimeMillis() + COMPLETED_TTL_MILLIS
		)
		sessionsById[sessionId] = completed
		return completed
	}

	@Synchronized
	internal fun get(sessionId: String): MinecraftRegistrationSession? {
		cleanup()
		return sessionsById[sessionId]
	}

	@Synchronized
	fun status(sessionId: String, name: String, uid: Long): MinecraftRegistrationSession? {
		cleanup()
		return sessionsById[sessionId]?.takeIf {
			it.name.equals(name, ignoreCase = true) && it.uid == uid
		}
	}

	/**
	 * Atomically consumes a passed session as the one-time registration proof.
	 * Failed, unfinished, mismatched, and already-consumed sessions are rejected.
	 */
	@Synchronized
	fun consumePassed(sessionId: String?, name: String?, uid: Long?): Boolean {
		cleanup()
		if (sessionId.isNullOrBlank() || name.isNullOrBlank() || uid == null) return false
		val session = sessionsById[sessionId] ?: return false
		if (
			session.state != MinecraftRegistrationSessionState.COMPLETED ||
			session.passed != true ||
			!session.name.equals(name, ignoreCase = true) ||
			session.uid != uid
		) return false
		if (!sessionsById.remove(session.id, session)) return false
		sessionIdByName.remove(normalize(session.name), session.id)
		return true
	}

	private fun cleanup() {
		val now = System.currentTimeMillis()
		val expired = sessionsById.values.filter { it.expiresAt <= now }
		expired.forEach { session ->
			sessionsById.remove(session.id, session)
			sessionIdByName.remove(normalize(session.name), session.id)
		}
	}

	private fun normalize(name: String): String = name.lowercase()

	companion object {
		const val SESSION_TTL_MILLIS = 15 * 60 * 1000L
		const val CLAIMED_SESSION_TTL_MILLIS = 60 * 60 * 1000L
		const val COMPLETED_TTL_MILLIS = 10 * 60 * 1000L
	}
}
