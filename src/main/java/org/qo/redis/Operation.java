package org.qo.redis;

import org.qo.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import static org.qo.redis.Configuration.EnableRedis;
import static org.qo.redis.Configuration.pool;
public class Operation {

    public static void insert(String key, String value, int database) {
        if (!EnableRedis) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.select(database);
            jedis.set(key, value);
            jedis.expire(key, Configuration.EXPIRE_TIME);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }

    public static String get(String key, int database) {
        if (!EnableRedis) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.select(database);
            return jedis.get(key);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
        return null;
    }

    public static boolean exists(String key, int database) {
        if (!EnableRedis) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.select(database);
            return jedis.exists(key);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
            return false;
        }
    }

    public static void delete(String key, int database) {
        if (!EnableRedis) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.select(database);
            jedis.del(key);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
}
