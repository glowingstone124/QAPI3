package org.qo.redis;

import org.qo.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import static org.qo.redis.Configuration.EnableRedis;
import static org.qo.redis.Configuration.pool;

public class Operation {
    public static void insert(String key, String value) {
        if (!EnableRedis) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, value);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    public static String get(String key) {
        if (!EnableRedis) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
        return null;
    }
    public static boolean exists(String key) {
        if (!EnableRedis) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(key);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
            return false;
        }
    }
}