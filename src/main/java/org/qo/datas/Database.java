package org.qo.datas;

import java.sql.*;

public class Database {
    public static boolean SQLAvliable() {
        boolean success = true;
        try (Connection connection = ConnectionPool.getConnection()){
        } catch (SQLException e){
            success = false;
        }
        return success;
    }
}
