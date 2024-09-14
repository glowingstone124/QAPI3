package org.qo.redis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.qo.Logger;
import org.springframework.stereotype.Service;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.*;
import java.util.Objects;
@Service
public class Configuration {
    public static String HOST;
    public static int PORT;
    public static String PASSWORD;
    public static String PATH = "data/redis/config.json";
    public static boolean EnableRedis = true;
    public static JedisPool pool;
    public static int QO_REG_DATABASE = 0;
    public static int QOAPP_REG_DATABASE = 1;
    public static int QO_ONLINE_DATABASE = 2;
    public static long EXPIRE_TIME = 2 * 60 * 60L; // 2 Hour

    private static void initPool() throws IOException {
        Gson gson = new Gson();
        JsonObject cfgObj;
        File file = new File(PATH);
        if (!file.exists() || !file.isFile()) {
            Logger.log("ERROR: Redis Configuration doesn't exist. Disabling Redis...", Logger.LogLevel.ERROR);
            EnableRedis = false;
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(PATH))) {
            StringBuilder sb = new StringBuilder();
            br.lines().forEach(sb::append);
            cfgObj = gson.fromJson(sb.toString(), JsonObject.class);
        }

        HOST = cfgObj.get("url").getAsString();
        PORT = cfgObj.get("port").getAsInt();
        PASSWORD = cfgObj.get("password").getAsString();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(128);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);

        pool = new JedisPool(poolConfig, HOST, PORT,5000, PASSWORD);

        try (Jedis jedis = pool.getResource()) {
            jedis.auth(PASSWORD);
        } catch (JedisConnectionException | JedisDataException e) {
            Logger.log("ERROR: " + e.getMessage() + " Disabling Redis...", Logger.LogLevel.ERROR);
            EnableRedis = false;
            if (pool != null) {
                pool.close();
            }
        }
    }

    public static void init() throws IOException {
        if (!EnableRedis) {
            Logger.log("Redis is disabled by configuration.", Logger.LogLevel.INFO);
            return;
        }
        initPool();
    }


    public void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
