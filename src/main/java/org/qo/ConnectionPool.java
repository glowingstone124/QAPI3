package org.qo;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class ConnectionPool {
    public static DataSource ds = null;

    public static void init() {
        Properties pro = new Properties();
        try {
            pro.load(ConnectionPool.class.getClassLoader().getResourceAsStream("druid.properties"));
            ds = DruidDataSourceFactory.createDataSource(pro);
        } catch (Exception e) {
            Logger.log("数据库初始化失败", Logger.LogLevel.ERROR);
        }
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (ds == null) {
            throw new IllegalArgumentException("SQL服务没有初始化");
        }
        return ds.getConnection();
    }
}
