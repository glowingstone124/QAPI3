package org.qo.services.inventoryServices

import org.qo.utils.Funcs
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

data class InventoryViewRequest(
	val secret: String,
	val owner: String,
	val viewer: String,
	val expiresAt: Long,
	@Volatile var approved: Boolean = false
)

@Service
class InventoryViewRequestService {
	private val requests = ConcurrentHashMap<String, InventoryViewRequest>()

	@Synchronized
	fun create(owner: String, viewer: String): InventoryViewRequest? {
		cleanup()
		if (requests.size >= MAX_PENDING_REQUESTS || requests.values.any {
			it.owner.equals(owner, ignoreCase = true) && it.viewer.equals(viewer, ignoreCase = true)
		}) return null

		var secret: String
		do {
			secret = Funcs.generateRandomString(32)
		} while (requests.containsKey(secret))
		return InventoryViewRequest(secret, owner, viewer, System.currentTimeMillis() + REQUEST_TTL_MILLIS).also {
			requests[secret] = it
		}
	}

	fun status(secret: String): InventoryViewRequest? {
		cleanup()
		return requests[secret]
	}

	fun approve(secret: String): InventoryViewRequest? {
		cleanup()
		return requests[secret]?.also { it.approved = true }
	}

	fun consume(secret: String): InventoryViewRequest? {
		cleanup()
		val request = requests[secret]?.takeIf { it.approved } ?: return null
		return if (requests.remove(secret, request)) request else null
	}

	private fun cleanup() {
		val now = System.currentTimeMillis()
		requests.entries.removeIf { it.value.expiresAt <= now }
	}

	companion object {
		const val REQUEST_TTL_MILLIS = 6 * 60 * 1000L
		const val MAX_PENDING_REQUESTS = 20
	}
}
