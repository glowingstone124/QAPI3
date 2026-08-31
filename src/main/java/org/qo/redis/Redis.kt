package org.qo.redis

import org.qo.redis.Configuration.pool

class Redis {
	data class AtomicQuotaResult(
		val status: Long,
		val used: Long,
	)

	@JvmOverloads
	fun insert(key: String, value: String, database: Int, expires: Long = Configuration.EXPIRE_TIME): RedisResult<Unit> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null

			pool?.resource?.use { jedis ->
				jedis.select(database)
				jedis.set(key, value)
				jedis.expire(key, expires)
			} ?: throw IllegalStateException("Redis pool is not initialized.")
			Unit
		}
	}

	fun get(key: String, database: Int): RedisResult<String?> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null

			pool?.resource?.use { jedis ->
				jedis.select(database)
				jedis.get(key)
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}

	fun delete(key: String, database: Int): RedisResult<Unit> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null

			pool?.resource?.use { jedis ->
				jedis.select(database)
				jedis.del(key)
			} ?: throw IllegalStateException("Redis pool is not initialized.")
			Unit
		}
	}

	fun exists(key: String, database: Int): RedisResult<Boolean> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult false

			pool?.resource?.use { jedis ->
				jedis.select(database)
				jedis.exists(key)
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}
	fun setIfAbsentWithExpire(key: String, value: String, database: Int, expiresSeconds: Long): RedisResult<Boolean> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult true
			pool?.resource?.use { jedis ->
				jedis.select(database)
				val inserted = jedis.setnx(key, value) == 1L
				if (inserted) {
					jedis.expire(key, expiresSeconds)
				}
				inserted
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}
	fun incrWithExpire(key: String, database: Int, expiresSeconds: Long): RedisResult<Long> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null
			pool?.resource?.use { jedis ->
				jedis.select(database)
				val value = jedis.incr(key)
				if (value == 1L) {
					jedis.expire(key, expiresSeconds)
				}
				value
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}

	fun reserveDailyQuota(
		quotaKey: String,
		requestKey: String,
		database: Int,
		limit: Int,
		expiresAtEpochSeconds: Long,
	): RedisResult<AtomicQuotaResult> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null
			pool?.resource?.use { jedis ->
				jedis.select(database)
				val result = jedis.eval(
					"""
					if redis.call('EXISTS', KEYS[2]) == 1 then
					  local current = tonumber(redis.call('GET', KEYS[1]) or '0')
					  return {2, current}
					end
					local current = tonumber(redis.call('GET', KEYS[1]) or '0')
					if current >= tonumber(ARGV[1]) then
					  return {0, current}
					end
					current = redis.call('INCR', KEYS[1])
					redis.call('EXPIREAT', KEYS[1], ARGV[2])
					redis.call('SET', KEYS[2], 'reserved')
					redis.call('EXPIREAT', KEYS[2], ARGV[2])
					return {1, current}
					""".trimIndent(),
					listOf(quotaKey, requestKey),
					listOf(limit.toString(), expiresAtEpochSeconds.toString()),
				) as? List<*> ?: throw IllegalStateException("Unexpected Redis quota response")
				AtomicQuotaResult(
					status = (result.getOrNull(0) as? Number)?.toLong()
						?: throw IllegalStateException("Missing Redis quota status"),
					used = (result.getOrNull(1) as? Number)?.toLong()
						?: throw IllegalStateException("Missing Redis quota usage"),
				)
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}

	fun refundDailyQuota(
		quotaKey: String,
		requestKey: String,
		database: Int,
		expiresAtEpochSeconds: Long,
	): RedisResult<Long> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null
			pool?.resource?.use { jedis ->
				jedis.select(database)
				val result = jedis.eval(
					"""
					if redis.call('GET', KEYS[2]) ~= 'reserved' then
					  return tonumber(redis.call('GET', KEYS[1]) or '0')
					end
					local current = tonumber(redis.call('GET', KEYS[1]) or '0')
					if current > 0 then
					  current = redis.call('DECR', KEYS[1])
					end
					redis.call('SET', KEYS[2], 'refunded')
					redis.call('EXPIREAT', KEYS[2], ARGV[1])
					return current
					""".trimIndent(),
					listOf(quotaKey, requestKey),
					listOf(expiresAtEpochSeconds.toString()),
				) as? Number ?: throw IllegalStateException("Unexpected Redis quota refund response")
				result.toLong()
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}

	fun readDailyQuota(key: String, database: Int): RedisResult<Long> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null
			pool?.resource?.use { jedis ->
				jedis.select(database)
				jedis.get(key)?.toLongOrNull() ?: 0L
			} ?: throw IllegalStateException("Redis pool is not initialized.")
		}
	}

	class RedisResult<T>(private val executor: () -> T?) {
		private var errorHandler: ((Exception) -> Unit)? = null

		fun onException(handler: (Exception) -> Unit): T? {
			this.errorHandler = handler
			return execute()
		}

		fun ignoreException(): T? {
			return execute()
		}

		private fun execute(): T? {
			return try {
				executor()
			} catch (e: Exception) {
				errorHandler?.invoke(e)
				null
			}
		}
	}

}
