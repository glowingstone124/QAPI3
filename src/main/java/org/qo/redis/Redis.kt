package org.qo.redis

import org.qo.utils.Logger
import org.qo.redis.Configuration.pool
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.exceptions.JedisDataException

class Redis {
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
