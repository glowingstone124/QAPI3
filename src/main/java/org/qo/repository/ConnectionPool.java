package org.qo.repository;

import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.google.gson.Gson;
import org.qo.util.Logger;

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
            FileReader reader = new FileReader("data/sql/info.json");
            Map<String, String> config = gson.fromJson(reader, Map.class);

            Properties pro = new Properties();
            pro.setProperty("username", config.get("username"));
            pro.setProperty("password", config.get("password"));
            pro.setProperty("url", config.get("url"));
            pro.setProperty("driverClassName", "com.mysql.cj.jdbc.Driver");
            pro.setProperty("initialSize", "5");
            pro.setProperty("maxActive", "30");

            ds = DruidDataSourceFactory.createDataSource(pro);
        } catch (IOException e) {
            Logger.log("读取数据库配置文件失败: " + e.getMessage(), Logger.LogLevel.ERROR);
        } catch (Exception e) {
            Logger.log("数据库初始化失败: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (ds == null) {
            throw new IllegalArgumentException("SQL服务没有初始化");
        }
        return ds.getConnection();
    }
}
