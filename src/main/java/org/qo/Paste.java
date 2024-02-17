package org.qo;

import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;

import java.net.http.HttpRequest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Calendar;

import static org.qo.Algorithm.hashSHA256;
import static org.qo.UserProcess.*;

public class Paste {
    public String handle(HttpServletRequest request, String text) throws Exception {
        String route = genroute();
        Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
        String insertQuery = "INSERT INTO pastes (ip, route, content) VALUES (?, ?, ?)";
        PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);
        preparedStatement.setString(1, IPUtil.getIpAddr(request));
        preparedStatement.setString(2, route);
        preparedStatement.setString(3, text);
        preparedStatement.executeUpdate();
        preparedStatement.close();
        connection.close();
        JsonObject returnObj= new JsonObject();
        returnObj.addProperty("dist", route);
        returnObj.addProperty("code", 0);
        return returnObj.toString();
    }
    private String genroute() throws Exception {
        String characters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        SecureRandom random = new SecureRandom();
        StringBuilder randomString = new StringBuilder(16);

        Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
        do {
            randomString.setLength(0);
            for (int i = 0; i < 16; i++) {
                int randomIndex = random.nextInt(characters.length());
                randomString.append(characters.charAt(randomIndex));
            }
            String query = "SELECT * FROM pastes WHERE route = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, randomString.toString());
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        break;
                    }
                }
            }

        } while (true);

        connection.close();
        return randomString.toString();
    }
    public String getContent(String route) throws Exception{
        Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
        String result = null;
        String query = "SELECT * FROM pastes WHERE route = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(query);
        preparedStatement.setString(1, route);
        ResultSet resultSet = preparedStatement.executeQuery();
        if (resultSet.next()) {
            result = resultSet.getString("content");
        }
        resultSet.close();
        preparedStatement.close();
        connection.close();
        JsonObject respObj = new JsonObject();
        if (result.isEmpty()){
            respObj.addProperty("code", 404);
            return respObj.toString();
        }
        respObj.addProperty("code", 0);
        respObj.addProperty("content", result);

        return respObj.toString();
    }
}
