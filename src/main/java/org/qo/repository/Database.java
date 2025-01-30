package org.qo.repository;

import java.sql.Connection;
import java.sql.SQLException;

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
