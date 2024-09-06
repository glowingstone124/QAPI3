package org.qo.redis

import org.qo.Logger
import org.qo.redis.Configuration.pool
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.exceptions.JedisDataException

class Redis{

    fun insert(key: String, value: String, database: Int) {
        if (!Configuration.EnableRedis) {
            return
        }
        try {
            pool.resource.use { jedis ->
                jedis.select(database)
                jedis.set(key, value)
                jedis.expire(key, Configuration.EXPIRE_TIME)
            }
        } catch (e: JedisConnectionException) {
            Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
        } catch (e: JedisDataException) {
            Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
        }
    }

    fun get(key: String, database: Int): String? {
        if (!Configuration.EnableRedis) {
            return null
        }
        return try {
            pool.resource.use { jedis ->
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
            pool.resource.use { jedis ->
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
            pool.resource.use { jedis ->
                jedis.select(database)
                jedis.del(key)
            }
        } catch (e: JedisConnectionException) {
            Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
        } catch (e: JedisDataException) {
            Logger.log("ERROR: ${e.message}", Logger.LogLevel.ERROR)
        }
    }
}
