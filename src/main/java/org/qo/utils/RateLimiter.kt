package org.qo.utils

import org.qo.redis.Configuration
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import java.util.concurrent.ConcurrentHashMap

class RateLimiter {
	private val redis = Redis()
	private val localCounters = ConcurrentHashMap<String, Counter>()

	data class Rule(
		val pathPrefix: String,
		val limit: Int,
		val windowSeconds: Long
	)

	private data class Counter(
		val count: Int,
		val resetAt: Long
	)

	private val rules = listOf(
		Rule("/qo/game/login", 10, 60),
		Rule("/qo/upload/registry", 10, 30),
		Rule("/qo/upload/password", 10, 30),
		Rule("/qo/authorization/templogin", 20, 30),
		Rule("/qo/asking/ask", 5, 30),
		Rule("/qo/download/registry", 60, 30),
		Rule("/qo/download/name", 60, 30),
		Rule("/qo/download/status", 300, 10),
		Rule("/qo/download/getgametime", 60, 30),
		Rule("/qo/download/logingreeting", 60, 60),
		Rule("/qo/msglist/download", 120, 20),
		Rule("/qo/authorization/", 60, 60),
		Rule("/qo/", 400, 60)
	)

	fun allow(path: String, clientKey: String): Boolean {
		val rule = rules.firstOrNull { path.startsWith(it.pathPrefix) } ?: return true
		val key = "rate:${rule.pathPrefix}:${clientKey}"
		if (Configuration.EnableRedis) {
			val count = redis.incrWithExpire(key, DatabaseType.QO_RATE_LIMIT_DATABASE.value, rule.windowSeconds)
				.ignoreException() ?: return true
			return count <= rule.limit
		}
		return allowLocal(key, rule)
	}

	private fun allowLocal(key: String, rule: Rule): Boolean {
		val now = System.currentTimeMillis()
		val updated = localCounters.compute(key) { _, existing ->
			if (existing == null || now >= existing.resetAt) {
				Counter(1, now + rule.windowSeconds * 1000)
			} else {
				Counter(existing.count + 1, existing.resetAt)
			}
		} ?: return true
		return updated.count <= rule.limit
	}
}
