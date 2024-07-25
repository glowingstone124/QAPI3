package org.qo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qo.mail.Mail;
import org.qo.mail.MailPreset;
import org.qo.redis.Configuration;
import org.qo.redis.Operation;
import org.qo.server.AvatarCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.qo.Logger.LogLevel.*;
import static org.qo.Algorithm.hashSHA256;
import static org.qo.redis.Configuration.*;

@Service
public class UserProcess {
    public static final String SQL_CONFIGURATION = "data/sql/info.json";
    public static final String CODE_CONFIGURATION = "data/code.json";
    public static String jdbcUrl = getDatabaseInfo("url");
    public static ArrayList<Key> inventoryViewList = new ArrayList<>();
    public static String CODE = "null";
    public static String sqlusername = getDatabaseInfo("username");
    public static String sqlpassword = getDatabaseInfo("password");
    public static VirtualThreadExecutor virtualThreadExecutor = new VirtualThreadExecutor("SQLExec");
    public static String firstLoginSearch(String name, HttpServletRequest request) {
        try (Connection connection = ConnectionPool.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM forum WHERE username = ?")) {
            preparedStatement.setString(1, name);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    Boolean first = resultSet.getBoolean("firstLogin");
                    JSONObject responseJson = new JSONObject();
                    responseJson.put("code", 0);
                    responseJson.put("first", first);
                    return responseJson.toString();
                }
            }
            String updateQuery = "UPDATE forum SET firstLogin = ? WHERE username = ?";
            try (PreparedStatement apreparedStatement = connection.prepareStatement(updateQuery)) {
                apreparedStatement.setBoolean(1, false);
                apreparedStatement.setString(2, name);
                Logger.log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin.", INFO);
                int rowsAffected = apreparedStatement.executeUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("first", -1);
        Logger.log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin but unsuccessful.", INFO);
        return responseJson.toString();
    }
    public static String fetchMyinfo(String name, HttpServletRequest request) {
        String date;
        Boolean premium;
        Boolean donate;
        try (Connection connection = ConnectionPool.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM forum WHERE username = ?")) {
            preparedStatement.setString(1, name);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
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
                    // No rows found for the given username”
                    JSONObject responseJson = new JSONObject();
                    responseJson.put("code", -1);
                    Logger.log(IPUtil.getIpAddr(request) + "username " + name + " qureied myinfo, but unsuccessful.", INFO);
                    return responseJson.toString();
                }
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
            Logger.log("ERROR: SQL CONFIG NOT FOUND", ERROR);
        }
        switch (type) {
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

    public static String getServerStats() throws IOException {
        JSONArray statusArray = new JSONArray(Files.readString(Path.of("stat.json")));
        for (int i = 0; i < statusArray.length(); i++) {
            JSONObject event = statusArray.getJSONObject(i);
            String title = event.getString("title");
            String date = event.getString("date");
            String author = event.getString("author");
            String summary = event.getString("summary");
            if (summary == null || author == null || date == null || title == null){
                Logger.log("INVALID Status Message found.", ERROR);
                return null;
            }
        }
        return statusArray.toString();
    }
    public static void handleTime(String name, int time) {
        try (Connection connection = ConnectionPool.getConnection()) {
            String checkQuery = "SELECT * FROM timeTables WHERE name=?";
            try (PreparedStatement checkStatement = connection.prepareStatement(checkQuery)) {
                checkStatement.setString(1, name);
                try (ResultSet resultSet = checkStatement.executeQuery()) {
                    if (resultSet.next()) {
                        int existingTime = resultSet.getInt("time");
                        int updatedTime = existingTime + time;
                        String updateQuery = "UPDATE timeTables SET time=? WHERE name=?";
                        try (PreparedStatement updateStatement = connection.prepareStatement(updateQuery)) {
                            updateStatement.setInt(1, updatedTime);
                            updateStatement.setString(2, name);
                            updateStatement.executeUpdate();
                        }
                    } else {
                        String insertQuery = "INSERT INTO timeTables (name, time) VALUES (?, ?)";
                        try (PreparedStatement insertStatement = connection.prepareStatement(insertQuery)) {
                            insertStatement.setString(1, name);
                            insertStatement.setInt(2, time);
                            insertStatement.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static JSONObject getTime(String username) {
        JSONObject result = null;
        try (Connection connection = ConnectionPool.getConnection()) {
            result = new JSONObject();
            String query = "SELECT * FROM timeTables WHERE name = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, username);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        int time = resultSet.getInt("time");
                        result.put("name", username);
                        result.put("time", time);
                    } else {
                        result.put("error", -1);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    public static boolean queryForum(String username) throws Exception {
        boolean resultExists = false;

        try (Connection connection = ConnectionPool.getConnection()) {
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

    public static String queryReg(String name) {
        if(Operation.exists("users:" +name, QO_REG_DATABASE)) {
           JsonObject retObj = (JsonObject) JsonParser.parseString(Objects.requireNonNull(Operation.get(name, QO_REG_DATABASE)));
           retObj.addProperty("code", 0);
           return retObj.toString();
        }
        try (Connection connection = ConnectionPool.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT uid,frozen,economy FROM users WHERE username = ?")) {
            preparedStatement.setString(1, name);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    Long uid = resultSet.getLong("uid");
                    Boolean frozen = resultSet.getBoolean("frozen");
                    int eco = resultSet.getInt("economy");

                    JSONObject responseJson = new JSONObject();
                    responseJson.put("frozen", frozen);
                    responseJson.put("qq", uid);
                    responseJson.put("economy", eco);
                    Operation.insert("user:" + name, responseJson.toString(), QO_REG_DATABASE);
                    responseJson.put("code", 0);
                    return responseJson.toString();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("qq", -1);
        return responseJson.toString();
    }
    
    public static String queryReg(long qq) {
        try (Connection connection = ConnectionPool.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT username,frozen,economy FROM users WHERE uid = ?")) {
            preparedStatement.setLong(1, qq);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                   String username  = resultSet.getString("username");
                    Boolean frozen = resultSet.getBoolean("frozen");
                    int eco = resultSet.getInt("economy");

                    JSONObject responseJson = new JSONObject();
                    responseJson.put("code", 0);
                    responseJson.put("frozen", frozen);
                    responseJson.put("username", username);
                    responseJson.put("economy", eco);
                    return responseJson.toString();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("username", -1);
        return responseJson.toString();
    }
    public static void regforum(String username, String password) {
        virtualThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Calendar calendar = Calendar.getInstance();
                int year = calendar.get(Calendar.YEAR);
                int month = calendar.get(Calendar.MONTH) + 1;
                int day = calendar.get(Calendar.DAY_OF_MONTH);
                String date = year + "-" + month + "-" + day;
                String EncryptedPswd = hashSHA256(password);
                try (Connection connection = ConnectionPool.getConnection()) {
                    String insertQuery = "INSERT INTO forum (username, date, password, premium, donate, firstLogin, linkto, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement preparedStatement = connection.prepareStatement(insertQuery)) {
                        preparedStatement.setString(1, username);
                        preparedStatement.setString(2, date);
                        preparedStatement.setString(3, EncryptedPswd);
                        preparedStatement.setBoolean(4, false);
                        preparedStatement.setBoolean(5, false);
                        preparedStatement.setBoolean(6, true);
                        preparedStatement.setString(7, "EMPTY");
                        preparedStatement.setInt(8, 0);
                        preparedStatement.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });
    }
    public static ResponseEntity<String> regMinecraftUser(String name, Long uid, HttpServletRequest request, String appname) throws ExecutionException, InterruptedException {
        CompletableFuture<ResponseEntity<String>> future = CompletableFuture.supplyAsync(() -> {
            if (!UserProcess.dumplicateUID(uid) && name != null && uid != null) {
                try (Connection connection = ConnectionPool.getConnection()) {
                    String insertQuery = "INSERT INTO users (username, uid,frozen, remain, economy) VALUES (?, ?, ?, ?, ?)";
                    Link(name, appname);
                    try (PreparedStatement preparedStatement = connection.prepareStatement(insertQuery)) {
                        preparedStatement.setString(1, name);
                        preparedStatement.setLong(2, uid);
                        preparedStatement.setBoolean(3, false);
                        preparedStatement.setInt(4, 3);
                        preparedStatement.setInt(5, 0);
                        PoolUtils pu = new PoolUtils();
                        pu.submit(() -> {
                            JsonObject playerJson = new JsonObject();
                            playerJson.addProperty("qq", uid);
                            playerJson.addProperty("code", 0);
                            playerJson.addProperty("frozen", false);
                            playerJson.addProperty("economy", 0);
                            Operation.insert("user:" + name, playerJson.toString(), QO_REG_DATABASE);
                        });
                        int rowsAffected = preparedStatement.executeUpdate();
                        Logger.log(rowsAffected + " row(s) inserted." + "from " + IPUtil.getIpAddr(request), INFO);
                        Logger.log(name + " Registered.", INFO);
                        Mail mail = new Mail();
                        mail.send(uid + "@qq.com", "感谢您注册QO2账号", MailPreset.register);
                        UserProcess.insertIp(IPUtil.getIpAddr(request));
                        return ReturnInterface.success("Success!");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return ReturnInterface.failed("FAILED");
                }
            } else {
                return ReturnInterface.failed("FAILED");
            }
        });
        return future.get();
    }

    public static void deleteMinecraftUser(String name) {
        virtualThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (name != null && !name.trim().isEmpty()) {
                    try (Connection connection = ConnectionPool.getConnection()) {
                        String deleteQuery = "DELETE FROM users WHERE username = ?";
                        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteQuery)) {
                            preparedStatement.setString(1, name);
                            int rowsAffected = preparedStatement.executeUpdate();
                            if (rowsAffected > 0) {
                                System.out.println("User " + name + " deleted successfully!");
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("FAILED");
                    }
                } else {
                    System.out.println("Invalid username!");
                }
            }
        });
    }

    public static ResponseEntity<String> delMinecraftUser(String appname,String mcname) throws ExecutionException, InterruptedException {
        CompletableFuture<ResponseEntity<String>> future = CompletableFuture.supplyAsync(() -> {
            if (queryLink(appname).equals(mcname) && !Objects.equals(queryLink(appname), "EMPTY")){
                deleteMinecraftUser(appname);
                return ReturnInterface.success("SUCCESS.");
            }
            return ReturnInterface.denied("USERNAME NOT MATCH!");
        });
        return future.get();
    }
    public static String AvatarTrans(String name) throws Exception {
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

    public static String Link(String forum, String name) {
        try (Connection connection = ConnectionPool.getConnection()) {
            String query = "SELECT linkto FROM forum WHERE username = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, forum);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        String resultname = resultSet.getString("linkto");
                        if (Objects.equals(resultname, "EMPTY")) {
                            String updateQuery = "UPDATE forum SET linkto = ? WHERE username = ?";
                            try (PreparedStatement updateStatement = connection.prepareStatement(updateQuery)) {
                                updateStatement.setString(1, name);
                                updateStatement.setString(2, forum);
                                updateStatement.executeUpdate();
                                return "DONE";
                            }
                        } else {
                            return "ERROR: Already Linked";
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "failed";
    }


    public static String queryLink(String forum) {
        try (Connection connection = ConnectionPool.getConnection()) {
            String query = "SELECT linkto FROM forum WHERE username = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, forum);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("linkto");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static boolean insertIp(String ip) {
        try (Connection connection = ConnectionPool.getConnection()) {
            String query = "INSERT INTO iptable (ip) VALUES (?)";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, ip);
                int rowsAffected = preparedStatement.executeUpdate();
                return rowsAffected > 0;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static boolean dumplicateUID(long uid) {
        try (Connection connection = ConnectionPool.getConnection()) {
            String query = "SELECT * FROM users WHERE uid = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setLong(1, uid);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    return resultSet.next();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static boolean changeHash(String username, String hash) {
        boolean success = false;
        try (Connection connection = ConnectionPool.getConnection()) {
            String query = "UPDATE forum SET password = ? WHERE username = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, hash);
                preparedStatement.setString(2, username);
                int rowsUpdated = preparedStatement.executeUpdate();
                if (rowsUpdated > 0) {
                    success = true;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return success;
    }

    public static ResponseEntity<String> userLogin(String username, String password, HttpServletRequest request) {
        String hashedPassword = hashSHA256(password);
        String userKey = "app:" + username;
        if (Operation.exists(userKey, QOAPP_REG_DATABASE) && MessageDigest.isEqual(Operation.get(userKey, QOAPP_REG_DATABASE).getBytes(), hashedPassword.getBytes())) {
            Logger.log(IPUtil.getIpAddr(request) + " " + username + " login successful.", INFO);
            return ReturnInterface.success("成功");
        }
        try (Connection connection = ConnectionPool.getConnection()) {
            String query = "SELECT username,password FROM forum WHERE username = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, username);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        String storedHashedPassword = resultSet.getString("password");
                        if (MessageDigest.isEqual(hashedPassword.getBytes(), storedHashedPassword.getBytes())) {
                            Operation.insert(userKey, storedHashedPassword, QOAPP_REG_DATABASE);
                            Logger.log(IPUtil.getIpAddr(request) + " " + username + " login successful.", ERROR);
                            return ReturnInterface.success("成功");
                        } else {
                            Logger.log("username " + username + " login failed.", ERROR);
                        }
                    } else {
                        Logger.log("username " + username + " login failed.", ERROR);
                        return ReturnInterface.denied("登录失败");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ReturnInterface.failed("internal error");
        }
        return ReturnInterface.failed("internal error");
    }

    public static String operateEco(String username, int value, opEco operation) {
        String updateSql = "UPDATE users SET economy = economy - ? WHERE username = ?";
        try (Connection connection = ConnectionPool.getConnection()) {
            if (operation ==  opEco.ADD) {
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

    /**
     * @param ip       登录ip
     * @param username 登录用户名
     */
    public static void insertLoginIP(String ip, String username) throws Exception {
        virtualThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String query = "SELECT username, uid FROM users WHERE username = ?";
                String insert = "INSERT INTO iptable (username, ip) VALUES (?, ?)";
                try (Connection connection = ConnectionPool.getConnection();
                     PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                    preparedStatement.setString(1, username);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        if (resultSet.next()) {
                            try (PreparedStatement preparedStatement1 = connection.prepareStatement(insert)) {
                                preparedStatement1.setString(1, username);
                                preparedStatement1.setString(2, ip);
                                preparedStatement1.executeUpdate();
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * @param username 查询用户名
     */
    public static String getLatestLoginIP(String username) {
        String query = "SELECT ip FROM loginip WHERE username = ?";
        try (Connection connection = ConnectionPool.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, username);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("ip");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "error";
    }
    public static boolean insertInventoryViewRequest(String key, String viewer, String sender) {
        Key request = new Key();
        request.viewer = viewer;
        request.provider = sender;
        request.key = key;
        request.expire = System.currentTimeMillis() + 360000;
        request.approve = false;
        if (inventoryViewList.size() >= 20) {
            return false;
        }

        for (Key obj : inventoryViewList) {
            if (obj.equals(request)) {
                return false;
            }
        }

        inventoryViewList.add(request);
        return true;
    }
    public static void approveInventoryViewRequest(String secret){
        for (Key obj : inventoryViewList) {
            if (Objects.equals(obj.key, secret)) {
                obj.approve = true;
            }
        }
    }
    public static String InventoryViewStatus(String key){
        JsonObject retObj = new JsonObject();
        int appr = 1;
        String viewer = "";
        for (Key obj : inventoryViewList) {
            if (Objects.equals(obj.key, key)){
                if (obj.approve){
                    appr = 0;
                }
                viewer = obj.viewer;
            }
        }
        retObj.addProperty("approved", appr);
        retObj.addProperty("viewer", viewer);
        return retObj.toString();
    }
    public enum opEco {
        ADD,
        SUB,
        MINUS
    }
    public static class Key {
        String viewer;
        String provider;
        String key;
        boolean approve;
        long expire;

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Key key1 = (Key) obj;
            return viewer.equals(key1.viewer) && provider.equals(key1.provider);
        }

        @Override
        public int hashCode() {
            int result = viewer.hashCode();
            result = 31 * result + provider.hashCode();
            result = 31 * result + key.hashCode();
            return result;
        }
    }
    public static void handlePlayerOnline(String name){
        if (!Operation.exists("online" + name, QO_ONLINE_DATABASE)){
            Operation.insert("online" + name, "true", QO_ONLINE_DATABASE);
        }
    }
    public static void handlePlayerOffline(String name){
        if (Operation.exists("online" + name, QO_ONLINE_DATABASE)){
           Operation.delete("online" + name, QO_ONLINE_DATABASE);
        }
    }
}
