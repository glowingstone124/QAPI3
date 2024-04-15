package org.qo;

import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONException;
import org.json.JSONObject;
import org.qo.mail.Mail;
import org.qo.mail.MailPreset;
import org.qo.server.AvatarCache;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.qo.Logger.LogLevel.*;
import static org.qo.Algorithm.hashSHA256;


public class UserProcess {
    public static final String SQL_CONFIGURATION = "data/sql/info.json";
    public static final String CODE_CONFIGURATION = "data/code.json";
    public static String jdbcUrl = getDatabaseInfo("url");
    public static String CODE = "null";
    public static String sqlusername = getDatabaseInfo("username");
    public static String sqlpassword = getDatabaseInfo("password");
    private static int POOL_SIZE = 70;
    private static BlockingQueue<Connection> connectionPool = new ArrayBlockingQueue<>(POOL_SIZE);

    static {
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
                connectionPool.offer(connection);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize connection pool.");
        }
    }

    public static String firstLoginSearch(String name, HttpServletRequest request) {
        Connection connection = null;
        try {
            connection = connectionPool.take();

            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, name);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Boolean first = resultSet.getBoolean("firstLogin");
                JSONObject responseJson = new JSONObject();
                responseJson.put("code", 0);
                responseJson.put("first", first);
                resultSet.close();
                preparedStatement.close();

                return responseJson.toString();
            }

            String updateQuery = "UPDATE forum SET firstLogin = ? WHERE username = ?";
            PreparedStatement apreparedStatement = connection.prepareStatement(updateQuery);
            apreparedStatement.setBoolean(1, false);
            apreparedStatement.setString(2, name);
            apreparedStatement.executeUpdate(); // 执行更新操作
            apreparedStatement.close(); // 关闭更新的 preparedStatement

            Logger.log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin.", INFO);
        } catch (Exception e) {
            e.printStackTrace();
            Logger.log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin but unsuccessful.", INFO);
        } finally {
            if (connection != null) {
                connectionPool.offer(connection);
            }
        }

        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("first", -1);
        return responseJson.toString();
    }

    public static String fetchMyinfo(String name, HttpServletRequest request) {
        if (name.isEmpty()) {
            JSONObject responseJson = new JSONObject();
            responseJson.put("code", -1);
            Logger.log(IPUtil.getIpAddr(request) + "username is empty when qureied myinfo.", INFO);
            return responseJson.toString();
        }

        Connection connection = null;
        try {
            connection = connectionPool.take();

            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, name);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String date = resultSet.getString("date");
                Boolean premium = resultSet.getBoolean("premium");
                Boolean donate = resultSet.getBoolean("donate");
                String linkto = resultSet.getString("linkto");
                JSONObject responseJson = new JSONObject();
                responseJson.put("date", date);
                responseJson.put("premium", premium);
                responseJson.put("donate", donate);
                responseJson.put("code", 0);
                responseJson.put("linkto", linkto);
                responseJson.put("username", name);
                Logger.log(IPUtil.getIpAddr(request) + "username " + name + " qureied myinfo.", INFO);
                return responseJson.toString();
            } else {
                JSONObject responseJson = new JSONObject();
                responseJson.put("code", -1);
                Logger.log(IPUtil.getIpAddr(request) + "username " + name + " qureied myinfo, but unsuccessful.", INFO);
                return responseJson.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Logger.log("username " + name + " qureied myinfo, but unsuccessful.", INFO);
            JSONObject responseJson = new JSONObject();
            responseJson.put("code", -1);
            return responseJson.toString();
        } finally {
            if (connection != null) {
                connectionPool.offer(connection);
            }
        }
    }

    public static String getDatabaseInfo(String type) {
        JSONObject sqlObject = null;
        try {
            sqlObject = new JSONObject(Files.readString(Path.of(SQL_CONFIGURATION)));
        } catch (IOException e) {
            Logger.log("ERROR: SQL CONFIG NOT FOUND",ERROR);
        }
        switch (type){
            case "password":
                return sqlObject.getString("password");
            case "username":
                return sqlObject.getString("username");
            case "url":
                return sqlObject.getString("url");
            default:
                return null;
        }
    }
    public static void handleTime(String name, int time) {
    try {
        Connection connection = connectionPool.take();
        String checkQuery = "SELECT * FROM timeTables WHERE name=?";
        PreparedStatement checkStatement = connection.prepareStatement(checkQuery);
        checkStatement.setString(1, name);
        ResultSet resultSet = checkStatement.executeQuery();

        if (resultSet.next()) {
            int existingTime = resultSet.getInt("time");
            int updatedTime = existingTime + time;

            String updateQuery = "UPDATE timeTables SET time=? WHERE name=?";
            PreparedStatement updateStatement = connection.prepareStatement(updateQuery);
            updateStatement.setInt(1, updatedTime);
            updateStatement.setString(2, name);
            updateStatement.executeUpdate();
            updateStatement.close();
        } else {
            String insertQuery = "INSERT INTO timeTables (name, time) VALUES (?, ?)";
            PreparedStatement insertStatement = connection.prepareStatement(insertQuery);
            insertStatement.setString(1, name);
            insertStatement.setInt(2, time);
            insertStatement.executeUpdate();
            insertStatement.close();
        }

        resultSet.close();
        checkStatement.close();
        connectionPool.offer(connection);
    } catch (SQLException e) {
        e.printStackTrace();
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    }
    }

    public static JSONObject getTime(String username) {
        JSONObject result = null;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword)) {
            result = new JSONObject();
            String query = "SELECT * FROM timeTables WHERE name = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            preparedStatement.setString(1, username);

            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                int time = resultSet.getInt("time");
                result.put("name", username);
                result.put("time", time);
            } else {
                result.put("error", -1);
            }

            resultSet.close();
            preparedStatement.close();
        } catch (SQLException e) {
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    public static boolean queryForum(String username) throws Exception {
        boolean resultExists = false;
        Connection connection = connectionPool.take();
        try  {
            String selectQuery = "SELECT * FROM forum WHERE username = ?";
            Logger.log(selectQuery, INFO);

            try (PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
                preparedStatement.setString(1, username);

                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    resultExists = resultSet.next();
                }
            }
        } finally {
            connectionPool.offer(connection);
        }
        return resultExists;
    }
    public static String queryHash(String hash) throws Exception {
        String jsonContent = new String(Files.readAllBytes(Path.of(CODE)), StandardCharsets.UTF_8);
        JSONObject codeObject = new JSONObject(jsonContent);

        if (codeObject.has(hash)) {
            String username = codeObject.getString(hash);
            String finalOutput = username;
            Files.write(Path.of(CODE), codeObject.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println(username);
            return finalOutput;
        } else {
            System.out.println("mismatched");
            return null;
        }
    }
    public static String queryReg(String name) throws Exception{
        Connection connection = connectionPool.take();
        try {
            String query = "SELECT * FROM users WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, name);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Long uid = resultSet.getLong("uid");
                Boolean frozen = resultSet.getBoolean("frozen");
                int eco = resultSet.getInt("economy");

                JSONObject responseJson = new JSONObject();
                responseJson.put("code", 0);
                responseJson.put("frozen", frozen);
                responseJson.put("qq", uid);
                responseJson.put("economy", eco);
                return responseJson.toString();
            }
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            connectionPool.offer(connection);
        }
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("qq", -1);
        return responseJson.toString();
    }
    public static void regforum(String username, String password) throws Exception{
        // 解析JSON数据为JSONArray
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1; // 注意月份是从0开始计数的
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        String date = year + "-" + month + "-" + day;
        String EncryptedPswd = hashSHA256(password);
        Connection connection = connectionPool.take();
        String insertQuery = "INSERT INTO forum (username, date, password, premium, donate, firstLogin, linkto, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);
        preparedStatement.setString(1, username);
        preparedStatement.setString(2, date);
        preparedStatement.setString(3, EncryptedPswd);
        preparedStatement.setBoolean(4, false);
        preparedStatement.setBoolean(5, false);
        preparedStatement.setBoolean(6, true);
        preparedStatement.setString(7, "EMPTY");
        preparedStatement.setInt(8, 0);
        preparedStatement.executeUpdate();
        // 关闭资源
        preparedStatement.close();
        connectionPool.offer(connection);
    }
    public static String regMinecraftUser(String name, Long uid, HttpServletRequest request, String appname){
        if (!UserProcess.dumplicateUID(uid) && name != null && uid != null) {
            try {
                Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
                String insertQuery = "INSERT INTO users (username, uid,frozen, remain, economy) VALUES (?, ?, ?, ?, ?)";
                Link(name, appname);
                PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);
                preparedStatement.setString(1, name);preparedStatement.setLong(2, uid);
                preparedStatement.setBoolean(3, false);
                preparedStatement.setInt(4, 3);preparedStatement.setInt(5,0);
                int rowsAffected = preparedStatement.executeUpdate();
                System.out.println(rowsAffected + " row(s) inserted." + "from " + IPUtil.getIpAddr(request));
                System.out.println(name + " Registered.");
                Mail mail = new Mail();
                mail.send(uid + "@qq.com", "感谢您注册QO2账号", MailPreset.register);
                preparedStatement.close();
                connection.close();UserProcess.insertIp(IPUtil.getIpAddr(request));
                return ReturnInterface.success("Success!");
            } catch (Exception e) {
                e.printStackTrace();
                return ReturnInterface.failed("FAILED");
            }
        }
        return ReturnInterface.failed("FAILED");
    }
    public static String AvatarTrans(String name) throws Exception{
        String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
        String avatarURL = "https://playerdb.co/api/player/minecraft/";
        if (!AvatarCache.has(name)) {
            JSONObject uuidobj = new JSONObject(Request.sendGetRequest(apiURL));
            String uuid = uuidobj.getString("id");
            JSONObject playerUUIDobj = new JSONObject(Request.sendGetRequest(avatarURL + uuid));
            if (playerUUIDobj.getBoolean("success")) {
                JSONObject player = playerUUIDobj.getJSONObject("data").getJSONObject("player");
                JSONObject returnObject = new JSONObject();
                returnObject.put("url", player.getString("avatar"));
                returnObject.put("name", player.getString("username"));
                AvatarCache.cache(player.getString("avatar"), player.getString("username"));
                return returnObject.toString();
            } else {
                JSONObject returnObject = new JSONObject();
                returnObject.put("url", "https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7");
                returnObject.put("name", name);
                return returnObject.toString();
            }
        }
        JSONObject returnObject = new JSONObject();
        returnObject.put("url", "https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7");
        returnObject.put("name", name);
        return returnObject.toString();
    }
    public static String downloadMemorial() throws IOException {
        String filePath = "data/memorial.json";

        // Read the content of the file and return it as the response
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line);
            }
            return content.toString();
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }
    }
    public static String Link(String forum, String name){
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT linkto FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, forum);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String resultname = resultSet.getString("linkto");
                if (Objects.equals(resultname, "EMPTY")) {
                    String updateQuery = "UPDATE forum SET linkto = ? WHERE username = ?";
                    PreparedStatement updateStatement = connection.prepareStatement(updateQuery);
                    while (resultSet.next()) {
                        String username = forum;
                        String linkAccount = name;
                        updateStatement.setString(1, linkAccount);
                        updateStatement.setString(2, username);
                        updateStatement.executeUpdate();
                    }
                    return "DONE";
                } else {
                    return "ERROR: Already Linked";
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }
        return "failed";
    }
    public static String queryLink(String forum){
        try{
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, forum);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("linkto");
            }
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    public static boolean insertIp(String ip) throws Exception{
        Connection connection = connectionPool.take();
        try {
            String query = "INSERT INTO iptable (ip) VALUES (?)";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, ip);
            int rowsAffected = preparedStatement.executeUpdate();
            preparedStatement.close();
            connection.close();
            return rowsAffected > 0;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            connectionPool.offer(connection);
        }
        return false;
    }

    public static boolean dumplicateUID(long uid){
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT * FROM users WHERE uid = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setLong(1, uid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return true;
            }
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
    public static boolean changeHash(String username, String hash) {
        boolean success = false;
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "UPDATE forum SET password = ? WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, hash);
            preparedStatement.setString(2, username);
            int rowsUpdated = preparedStatement.executeUpdate();
            if (rowsUpdated > 0) {
                success = true;
            } else {

            }
            preparedStatement.close();
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return success;
    }
    public static String userLogin(String username, String password, HttpServletRequest request){
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, username);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String storedHashedPassword = resultSet.getString("password");
                String encryptPswd = hashSHA256(password);
                if (Objects.equals(encryptPswd, storedHashedPassword)) {
                    Logger.log(IPUtil.getIpAddr(request) + " "  + username + " login successful.", ERROR);
                    return ReturnInterface.success("成功");
                } else {
                    try (FileWriter writer = new FileWriter("login.log", true)) {
                        java.util.Date now = new Date();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        String timestamp = sdf.format(now);
                        String logMessage = "[" + timestamp + "] 用户 " + username + " 使用错误密码" + hashSHA256(password) + " 登录\n";
                        writer.write(logMessage);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                Logger.log("username " + username + " login failed.", ERROR);
                return ReturnInterface.denied("登录失败");
            }
            connection.close();
            preparedStatement.close();
        } catch (SQLException e) {
            e.printStackTrace();
            JSONObject responseJson = new JSONObject();
            responseJson.put("code", -1);
            return responseJson.toString();
        }
        return ReturnInterface.failed("NULL");
    }
    public static String operateEco(String username, int value, opEco operation){
        String updateSql = "UPDATE users SET economy = economy - ? WHERE username = ?";
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            if (operation == opEco.ADD) {
                updateSql = "UPDATE users SET economy = economy + ? WHERE username = ?";
            }
            try (PreparedStatement preparedStatement = connection.prepareStatement(updateSql)) {
                preparedStatement.setInt(1, value);
                preparedStatement.setString(2, username);
                int rowsUpdated = preparedStatement.executeUpdate();
                if (rowsUpdated > 0) {
                    return "success";
                } else {
                    return "failed";
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "failed";
    }
    public enum opEco{
        ADD,
        REMOVE,
        MINUS
    }
    /**
     * @param ip 登录ip
     * @param username 登录用户名
     */
    public static void insertLoginIP(String ip, String username) throws Exception {
        String query = "SELECT username, uid FROM users WHERE username = ?";
        String insert = "INSERT INTO iptable (username, ip) VALUES (?, ?)";
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        try {
            connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, username);
            resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                preparedStatement = connection.prepareStatement(insert);
                preparedStatement.setString(1, username);
                preparedStatement.setString(2, ip);
                preparedStatement.executeUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    /**
     * @param username 查询用户名
     */
    public static String getLatestLoginIP(String username){
        String query = "SELECT ip FROM loginip WHERE username = ?";
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        try {
            connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, username);
            resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("ip");
            }
        } catch (Exception e){
            e.printStackTrace();
        } finally {
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        return "undefined";
    }
}