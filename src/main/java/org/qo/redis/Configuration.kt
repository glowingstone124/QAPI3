package org.qo.redis

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.Logger
import redis.clients.jedis.*
import redis.clients.jedis.exceptions.JedisConnectionException
import redis.clients.jedis.exceptions.JedisDataException
import java.io.*

object Configuration {
    var HOST: String? = null
    var PORT: Int = 6379
    var PASSWORD: String? = null
    val PATH = "data/redis/config.json"
    var EnableRedis = true
    var pool: JedisPool? = null
    val QO_REG_DATABASE = 0
    val QOAPP_REG_DATABASE = 1
    val QO_ONLINE_DATABASE = 2
    val EXPIRE_TIME = 2 * 60 * 60L // 2 Hours

    @Throws(IOException::class)
    private fun initPool() {
        val gson = Gson()
        val file = File(PATH)

        if (!file.exists() || !file.isFile) {
            Logger.log("ERROR: Redis Configuration doesn't exist. Disabling Redis...", Logger.LogLevel.ERROR)
            EnableRedis = false
            return
        }

        val cfgObj: JsonObject = BufferedReader(FileReader(PATH)).use { br ->
            val sb = StringBuilder()
            br.lines().forEach { sb.append(it) }
            gson.fromJson(sb.toString(), JsonObject::class.java)
        }

        HOST = cfgObj.get("url")?.asString
        PORT = cfgObj.get("port")?.asInt ?: 6379
        PASSWORD = cfgObj.get("password")?.asString

        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 128
            maxIdle = 128
            minIdle = 16
            testOnBorrow = true
            testOnReturn = true
            testWhileIdle = true
        }

        pool = JedisPool(poolConfig, HOST, PORT, 5000, PASSWORD)

        try {
            pool?.resource?.use { jedis ->
                jedis.auth(PASSWORD)
            }
        } catch (e: JedisConnectionException) {
            Logger.log("ERROR: ${e.message}. Disabling Redis...", Logger.LogLevel.ERROR)
            EnableRedis = false
            pool?.close()
        } catch (e: JedisDataException) {
            Logger.log("ERROR: ${e.message}. Disabling Redis...", Logger.LogLevel.ERROR)
            EnableRedis = false
            pool?.close()
        }
    }

    @Throws(IOException::class)
    fun init() {
        if (!EnableRedis) {
            Logger.log("Redis is disabled by configuration.", Logger.LogLevel.INFO)
            return
        }
        initPool()
    }

    fun close() {
        pool?.close()
    }
}
