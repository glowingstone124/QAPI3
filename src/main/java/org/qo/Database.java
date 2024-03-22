package org.qo;

import java.sql.*;
import static org.qo.UserProcess.*;

public class Database {
    public static boolean SQLAvliable() {
        boolean success = true;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword)){
        } catch (SQLException e){
            success = false;
        }
        return success;
    }
}
