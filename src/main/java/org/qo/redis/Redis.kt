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

	fun getAndDelete(key: String, database: Int): RedisResult<String?> {
		return RedisResult {
			if (!Configuration.EnableRedis) return@RedisResult null
			pool?.resource?.use { jedis ->
				jedis.select(database)
				repeat(4) {
					jedis.watch(key)
					val value = jedis.get(key)
					if (value == null) {
						jedis.unwatch()
						return@use null
					}
					val transaction = jedis.multi()
					transaction.del(key)
					if (transaction.exec() != null) return@use value
				}
				jedis.unwatch()
				null
			} ?: throw IllegalStateException("Redis pool is not initialized.")
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
				repeat(8) {
					jedis.watch(quotaKey, requestKey)
					val current = jedis.get(quotaKey)?.toLongOrNull() ?: 0L
					if (jedis.exists(requestKey)) {
						jedis.unwatch()
						return@use AtomicQuotaResult(status = 2L, used = current)
					}
					if (current >= limit) {
						jedis.unwatch()
						return@use AtomicQuotaResult(status = 0L, used = current)
					}
					val transaction = jedis.multi()
					transaction.incr(quotaKey)
					transaction.expireAt(quotaKey, expiresAtEpochSeconds)
					transaction.set(requestKey, "reserved")
					transaction.expireAt(requestKey, expiresAtEpochSeconds)
					val result = transaction.exec() ?: return@repeat
					val used = (result.firstOrNull() as? Number)?.toLong()
						?: throw IllegalStateException("Unexpected Redis quota response")
					return@use AtomicQuotaResult(status = 1L, used = used)
				}
				jedis.unwatch()
				throw IllegalStateException("Redis quota transaction was repeatedly contended")
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
				repeat(8) {
					jedis.watch(quotaKey, requestKey)
					val current = jedis.get(quotaKey)?.toLongOrNull() ?: 0L
					if (jedis.get(requestKey) != "reserved") {
						jedis.unwatch()
						return@use current
					}
					val transaction = jedis.multi()
					if (current > 0L) transaction.decr(quotaKey)
					transaction.set(requestKey, "refunded")
					transaction.expireAt(requestKey, expiresAtEpochSeconds)
					val result = transaction.exec() ?: return@repeat
					return@use if (current > 0L) {
						(result.firstOrNull() as? Number)?.toLong()
							?: throw IllegalStateException("Unexpected Redis quota refund response")
					} else {
						0L
					}
				}
				jedis.unwatch()
				throw IllegalStateException("Redis quota refund transaction was repeatedly contended")
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
