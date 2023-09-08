package org.qo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Calendar;
import java.util.Objects;

public class UserProcess {
    private static final String FILE_PATH = "usermap.json";
    private static final String SERVER_FILE_PATH = "playermap.json";
    public static String jdbcUrl = "jdbc:mysql://localhost:3306/qouser";
    public static String sqlusername = "root";
    public static String sqlpassword = "05b028772401827c";

    public static boolean queryForum(String username) throws Exception {
        boolean resultExists = false;

        try (Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword)) {
            String selectQuery = "SELECT * FROM forum WHERE username = ?";
            Logger.Log(selectQuery, 0);

            try (PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
                preparedStatement.setString(1, username);

                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    resultExists = resultSet.next();
                }
            }
        }
        return resultExists;
    }

    public static boolean forumUserExist(String username) throws IOException {
        // 读取现有的usermap.json文件内容
        String content = readFile(FILE_PATH);
        JSONObject usermapJson = new JSONObject(content);

        // 检查用户是否存在于JSON对象的键集合中
        return usermapJson.has(username);
    }
    public static boolean serverUserExist(String username) throws IOException {
        // 读取现有的usermap.json文件内容
        String content = readFile(SERVER_FILE_PATH);
        JSONObject usermapJson = new JSONObject(content);

        // 检查用户是否存在于JSON对象的键集合中
        return usermapJson.has(username);
    }
    public static boolean VerifyPSWD(String username, String inputPassword) throws IOException {
        try {
            String userContent = readFile(FILE_PATH);
            if (userContent != null) {
                JSONObject userProfiles = new JSONObject(userContent);
                if (userProfiles.has(username)) {
                    JSONObject userProfile = userProfiles.getJSONObject(username);
                    String storedPassword = userProfile.getString("password");
                    return inputPassword.equals(storedPassword);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return false;
    }
    public static String readFile(String filename) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(filename));
            StringBuilder content = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                content.append(line);
            }

            reader.close();

            return content.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
    public static String Link(String forum, String name){
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 准备查询语句
            String query = "SELECT linkto FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setString(1, forum);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String resultname = resultSet.getString("linkto");
                if (Objects.equals(resultname, "EMPTY")) {
                    String updateQuery = "UPDATE forum SET linkto = ? WHERE username = ?";
                    PreparedStatement updateStatement = connection.prepareStatement(updateQuery);

                    while (resultSet.next()) {
                        String username = forum;
                        String linkAccount = name;

                        // 更新数据库中的密码
                        updateStatement.setString(1, linkAccount);
                        updateStatement.setString(2, username);
                        updateStatement.executeUpdate();
                    }
                    return "DONE";
                } else {
                    // Value is not NULL, return the linkto value
                    return "ERROR: Already Linked";
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }
        return "failed";
    }
    public static String queryLink(String forum){
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 准备查询语句
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setString(1, forum);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();

            // 处理查询结果
            if (resultSet.next()) {
                return resultSet.getString("linkto");
            }
            // 关闭资源
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    public static boolean dumplicateUID(long uid){
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 准备查询语句
            String query = "SELECT * FROM users WHERE uid = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setLong(1, uid);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();

            // 处理查询结果
            if (resultSet.next()) {
                return true;
            }
            // 关闭资源
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
