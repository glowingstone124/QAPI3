package org.qo;

import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONException;
import org.json.JSONObject;
import org.qo.server.AvatarCache;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Calendar;
import java.util.Objects;
import static org.qo.Logger.LogLevel.*;
import static org.qo.Algorithm.hashSHA256;


public class UserProcess {
    public static final String CODE = "users/recoverycode/index.json";
    private static final String FILE_PATH = "usermap.json";
    private static final String SERVER_FILE_PATH = "playermap.json";
    public static final String SQL_CONFIGURATION = "data/sql/info.json";
    public static String jdbcUrl = getDatabaseInfo("url");
    public static String sqlusername = getDatabaseInfo("username");
    public static String sqlpassword = getDatabaseInfo("password");

    public static String firstLoginSearch(String name, HttpServletRequest request) {
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
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
            // 设置更新参数值
            apreparedStatement.setBoolean(1, false);
            apreparedStatement.setString(2, name);

            // 执行更新操作
            Logger.log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin.", INFO);
            int rowsAffected = apreparedStatement.executeUpdate();
            apreparedStatement.close();
            connection.close();
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("first", -1);
        Logger.log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin but unsuccessful.", INFO);
        return responseJson.toString();
    }
        public static String fetchMyinfo(String name, HttpServletRequest request) throws Exception {
            String date;
            Boolean premium;
            Boolean donate;
            if (name.isEmpty()) {

            }
            try {
                Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
                String query = "SELECT * FROM forum WHERE username = ?";
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                preparedStatement.setString(1, name);
                ResultSet resultSet = preparedStatement.executeQuery();
                if (resultSet.next()) {
                    date = resultSet.getString("date");
                    premium = resultSet.getBoolean("premium");
                    donate = resultSet.getBoolean("donate");
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
                    // No rows found for the given username
                    JSONObject responseJson = new JSONObject();
                    responseJson.put("code", -1);
                    Logger.log(IPUtil.getIpAddr(request) + "username " + name + " qureied myinfo, but unsuccessful.", INFO);
                    return responseJson.toString();
                }

            } catch (SQLException e) {
                e.printStackTrace();
                JSONObject responseJson = new JSONObject();
                Logger.log("username " + name + " qureied myinfo, but unsuccessful.", INFO);
                responseJson.put("code", -1);
                return responseJson.toString();
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
    public static void handleTime(String name, int time){
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            String query = "INSERT INTO timeTables (name, time) VALUES (?, ?)";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            preparedStatement.setString(1, name);
            preparedStatement.setInt(2, time);
            preparedStatement.executeUpdate();
            preparedStatement.close();
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
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
    public static boolean SQLAvliable() {
        boolean success = true;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword)){
        } catch (SQLException e){
            success = false;
        }
        return success;
    }
    public static boolean queryForum(String username) throws Exception {
        boolean resultExists = false;

        try (Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword)) {
            String selectQuery = "SELECT * FROM forum WHERE username = ?";
            Logger.log(selectQuery, INFO);

            try (PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
                preparedStatement.setString(1, username);

                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    resultExists = resultSet.next();
                }
            }
        }
        return resultExists;
    }
    public static String queryHash(String hash) throws Exception {
        String jsonContent = new String(Files.readAllBytes(Path.of(CODE)), StandardCharsets.UTF_8);
        JSONObject codeObject = new JSONObject(jsonContent);

        if (codeObject.has(hash)) {
            String username = codeObject.getString(hash);
            String finalOutput = username;
           // codeObject.remove(hash);
            Files.write(Path.of(CODE), codeObject.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println(username);
            return finalOutput;
        } else {
            System.out.println("mismatched");
            return null;
        }
    }
    public static String queryArticles(int ArticleID, int ArticleSheets) throws Exception {
        String ArticleSheet;
        switch (ArticleSheets){
            case 0:
                ArticleSheet = "serverArticles";
                break;
            case 1:
                ArticleSheet = "commandArticles";
                break;
            case 2:
                ArticleSheet = "attractionsArticles";
                break;
            case 3:
                ArticleSheet = "noticeArticles";
                break;
            default:
                ArticleSheet = null;
                break;
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword)) {
            String selectQuery = "SELECT * FROM " + ArticleSheet +" WHERE id = ?";

            try (PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
                preparedStatement.setInt(1, ArticleID);

                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        String resultName = resultSet.getString("Name");
                        return resultName;
                    } else {
                        return null;
                    }
                }
            }
        }
    }
    public static String queryReg(String name) throws Exception{
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
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
        Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
        // 准备插入语句
        String insertQuery = "INSERT INTO forum (username, date, password, premium, donate, firstLogin, linkto) VALUES (?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);
        // 设置参数值
        preparedStatement.setString(1, username);
        preparedStatement.setString(2, date);
        preparedStatement.setString(3, EncryptedPswd);
        preparedStatement.setBoolean(4, false);
        preparedStatement.setBoolean(5, false);
        preparedStatement.setBoolean(6, true);
        preparedStatement.setString(7, "EMPTY");
        preparedStatement.executeUpdate();
        // 关闭资源
        preparedStatement.close();
        connection.close();
    }
    public static String regMinecraftUser(String name, Long uid, HttpServletRequest request){
        if (!UserProcess.dumplicateUID(uid) && name != null && uid != null) {
                try {
                    Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
                    String insertQuery = "INSERT INTO users (username, uid,frozen, remain, economy) VALUES (?, ?, ?, ?, ?)";
                    PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);
                    preparedStatement.setString(1, name);
                    preparedStatement.setLong(2, uid);
                    preparedStatement.setBoolean(3, false);
                    preparedStatement.setInt(4, 3);
                    preparedStatement.setInt(5,0);
                    // 执行插入操作
                    int rowsAffected = preparedStatement.executeUpdate();
                    System.out.println(rowsAffected + " row(s) inserted." + "from " + IPUtil.getIpAddr(request));
                    System.out.println(name + " Registered.");
                    // 关闭资源
                    preparedStatement.close();
                    connection.close();
                    UserProcess.insertIp(IPUtil.getIpAddr(request));
                    return ReturnInterface.success("Success!");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return ReturnInterface.failed("FAILED");
            } else if(name.equals(null) || uid.equals(null)){
            Logger.log("Register ERROR: username or uid null", ERROR);
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
    public static boolean hasIp(String ip){
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT * FROM iptable WHERE ip = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, ip);
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
    public static boolean insertIp(String ip) {
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "INSERT INTO iptable (ip) VALUES (?)";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, ip);
            int rowsAffected = preparedStatement.executeUpdate();
            preparedStatement.close();
            connection.close();
            return rowsAffected > 0;
        } catch (Exception e) {
            e.printStackTrace();
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
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, username);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String storedHashedPassword = resultSet.getString("password");
                String encryptPswd = hashSHA256(password);
                if (Objects.equals(encryptPswd, storedHashedPassword)) {
                    Logger.log(IPUtil.getIpAddr(request) + " "  + username + " login successful.", ERROR);
                    return ReturnInterface.success("成功");
                }
            } else {
                Logger.log("username " + username + " login failed.", ERROR);
                return ReturnInterface.failed("登录失败");
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
}
