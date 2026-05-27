package org.qo.datas;

import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.google.gson.Gson;
import org.qo.utils.Logger;

import javax.sql.DataSource;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

public class ConnectionPool {
    public static DataSource ds = null;

    public static void init() {
        try {
            Gson gson = new Gson();
            Map<String, String> config;
            try (FileReader reader = new FileReader("data/sql/info.json")) {
                config = gson.fromJson(reader, Map.class);
            }

            Properties pro = new Properties();
            pro.setProperty("username", config.get("username"));
            pro.setProperty("password", config.get("password"));
            pro.setProperty("url", config.get("url"));
            pro.setProperty("driverClassName", "com.mysql.cj.jdbc.Driver");
            pro.setProperty("initialSize", "5");
            pro.setProperty("maxActive", "100");
            pro.setProperty("minIdle", "5");
            pro.setProperty("maxWait", "3000");
            pro.setProperty("validationQuery", "SELECT 1");
            pro.setProperty("testWhileIdle", "true");
            pro.setProperty("testOnBorrow", "false");
            pro.setProperty("testOnReturn", "false");
            pro.setProperty("timeBetweenEvictionRunsMillis", "60000");

            ds = DruidDataSourceFactory.createDataSource(pro);
        } catch (IOException e) {
            Logger.log("读取数据库配置文件失败: " + e.getMessage(), Logger.LogLevel.ERROR);
        } catch (Exception e) {
            Logger.log("数据库初始化失败: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }

    public static Connection getConnection() throws SQLException {
        if (ds == null) {
            throw new IllegalArgumentException("SQL服务没有初始化");
        }
        return ds.getConnection();
    }
}
