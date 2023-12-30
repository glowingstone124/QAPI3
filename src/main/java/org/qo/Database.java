package org.qo;

import java.sql.*;
import static org.qo.UserProcess.*;

public class Database {
    public static String query(String databaseName, Object queryKey, String columnName) {
        String result = null;
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        try {
            connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT " + columnName + " FROM " + databaseName + " WHERE " + queryKey + "=?";
            preparedStatement = connection.prepareStatement(query);
            preparedStatement.setObject(1, queryKey);
            resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                result = resultSet.getString(columnName);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                if (resultSet != null) resultSet.close();
                if (preparedStatement != null) preparedStatement.close();
                if (connection != null) connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return result;
    }
}
