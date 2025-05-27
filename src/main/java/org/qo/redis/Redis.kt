package org.qo.redis

import org.qo.utils.Logger
import org.qo.redis.Configuration.pool
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.exceptions.JedisDataException

class Redis {

	@JvmOverloads
	fun insert(key: String, value: String, database: Int, expires: Long = Configuration.EXPIRE_TIME) {
		if (!Configuration.EnableRedis) return

		try {
			pool?.resource?.use { jedis ->
				jedis.select(database)
				jedis.set(key, value)
				jedis.expire(key, expires)
			} ?: Logger.log("ERROR: Redis pool is not initialized.", Logger.LogLevel.ERROR)
		} catch (e: JedisConnectionException) {
			Logger.log("Redis connection error on key=$key db=$database: ${e.message}", Logger.LogLevel.ERROR)
		} catch (e: JedisDataException) {
			Logger.log("Redis data error on key=$key db=$database: ${e.message}", Logger.LogLevel.ERROR)
		}
	}


	fun get(key: String, database: Int): String? {
		if (!Configuration.EnableRedis) {
			return null
		}
		return try {
			pool!!.resource.use { jedis ->
				jedis.select(database)
				jedis.get(key)
			}
		} catch (e: JedisConnectionException) {
			Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
			null
		} catch (e: JedisDataException) {
			Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
			null
		}
	}

	fun exists(key: String, database: Int): Boolean {
		if (!Configuration.EnableRedis) {
			return false
		}
		return try {
			pool!!.resource.use { jedis ->
				jedis.select(database)
				jedis.exists(key)
			}
		} catch (e: JedisConnectionException) {
			Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
			false
		} catch (e: JedisDataException) {
			Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
			false
		}
	}

	fun delete(key: String, database: Int) {
		if (!Configuration.EnableRedis) {
			return
		}
		try {
			pool?.resource.use { jedis ->
				jedis?.select(database)
				jedis?.del(key)
			}
		} catch (e: JedisConnectionException) {
			Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
		} catch (e: JedisDataException) {
			Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
		}
	}
}
