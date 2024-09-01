package org.qo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.json.JSONArray;
import org.qo.orm.UserORM;
import org.qo.datas.Mapping.*;
import org.json.JSONObject;
import org.qo.mail.Mail;
import org.qo.mail.MailPreset;
import org.qo.redis.Operation;
import org.qo.server.AvatarCache;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;

import static org.qo.Logger.LogLevel.*;
import static org.qo.redis.Configuration.*;

@Service
public class UserProcess {
    public static final String CODE_CONFIGURATION = "data/code.json";
    public static ConcurrentLinkedDeque<registry_verify_class> verify_list = new ConcurrentLinkedDeque<>();
    public static ArrayList<Key> inventoryViewList = new ArrayList<>();
    public static Request request = new Request();
    public static String CODE = "null";
    public static UserORM userORM = new UserORM();
    static PoolUtils pu = new PoolUtils();
    public static VirtualThreadExecutor virtualThreadExecutor = new VirtualThreadExecutor("SQLExec");
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
            String checkQuery = "SELECT playtime FROM users WHERE username=?";
            try (PreparedStatement checkStatement = connection.prepareStatement(checkQuery)) {
                checkStatement.setString(1, name);
                try (ResultSet resultSet = checkStatement.executeQuery()) {
                    if (resultSet.next()) {
                        int existingTime = resultSet.getInt("playtime");
                        int updatedTime = existingTime + time;
                        String updateQuery = "UPDATE users SET playtime=? WHERE username=?";
                        try (PreparedStatement updateStatement = connection.prepareStatement(updateQuery)) {
                            updateStatement.setInt(1, updatedTime);
                            updateStatement.setString(2, name);
                            updateStatement.executeUpdate();
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
            String query = "SELECT playtime FROM users WHERE username = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, username);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        int time = resultSet.getInt("playtime");
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
        UserORM userORM = new UserORM();
        Users user = userORM.read(qq);
        JSONObject responseJson = new JSONObject();
        if (user != null) {
            String username  = user.getUsername();
            Boolean frozen = user.getFrozen();
            int eco = user.getEconomy();
            long playtime = user.getPlaytime();
            responseJson.put("code", 0);
            responseJson.put("frozen", frozen);
            responseJson.put("username", username);
            responseJson.put("economy", eco);
            responseJson.put("playtime", playtime);
            return responseJson.toString();
        }
        responseJson.put("code", 1);
        responseJson.put("username", -1);
        return responseJson.toString();
    }
    public static ResponseEntity<String> regMinecraftUser(String name, Long uid, HttpServletRequest request, String password) throws ExecutionException, InterruptedException {
        CompletableFuture<ResponseEntity<String>> future = CompletableFuture.supplyAsync(() -> {
            if (Objects.equals(userORM.read(uid), null)&& name != null && uid != null) {
                try {
                    userORM.create(new Users(
                            name,
                            uid,
                            true,
                            3,
                            0,
                            false,
                            0,
                            computePassword(password, true)
                    ));
                    String token = Algorithm.generateRandomString(16);
                    Msg.put("用户 " + uid + "注册了一个账号：" + name + "，若非本人操作请忽略，确认账号请在消息发出后2小时内输入/approve-register " + token);
                    verify_list.add(new registry_verify_class(name,token,uid,System.currentTimeMillis()));
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
                pu.submit(() -> {
                    JsonObject playerJson = new JsonObject();
                    playerJson.addProperty("qq", uid);
                    playerJson.addProperty("code", 0);
                    playerJson.addProperty("frozen", false);
                    playerJson.addProperty("pro", 0);
                    playerJson.addProperty("playtime",  0);
                    Operation.insert("user:" + name, playerJson.toString(), QO_REG_DATABASE);
                });
                Logger.log(name + " registered from " + IPUtil.getIpAddr(request), INFO);
                Mail mail = new Mail();
                mail.send(uid + "@qq.com", "感谢您注册QO2账号", MailPreset.register);
                return ReturnInterface.success("Success!");
            } else {
                return ReturnInterface.failed("FAILED");
            }
        });
        return future.get();
    }
    public static boolean validateMinecraftUser(String token, HttpServletRequest request, Long uid) {
        Iterator<registry_verify_class> iterator = verify_list.iterator();
        while (iterator.hasNext()) {
            registry_verify_class item = iterator.next();
            if (Objects.equals(item.token, token) && Objects.equals(item.uid, uid) && System.currentTimeMillis() - item.expiration < 7200000) {
                pu.submit(() -> {
                    String sql = "UPDATE users SET frozen = false WHERE uid = ?";
                    try (Connection connection = ConnectionPool.getConnection();
                         PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, uid);
                        statement.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
                return true;
            } else if (System.currentTimeMillis() - item.expiration > 7200000) {
                iterator.remove();
                return false;
            }
        }
        return false;
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
    public static String AvatarTrans(String name) throws Exception {
        String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
        String avatarURL = "https://playerdb.co/api/player/minecraft/";
        if (!AvatarCache.has(name)) {
            JSONObject uuidobj = new JSONObject(request.sendGetRequest(apiURL));
            String uuid = uuidobj.getString("id");
            JSONObject playerUUIDobj = new JSONObject(request.sendGetRequest(avatarURL + uuid));
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
    /**
     * @param ip       登录ip
     * @param username 登录用户名
     */
    @Deprecated
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
    @Deprecated
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
    public static boolean verifyPasswd(String username, String password) throws NoSuchAlgorithmException {
        Users user = userORM.read(username);
        if (user == null) {
            return false;
        }
        String user_salt = user.getPassword().split("\\$")[2];
        if (Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + user_salt ,  MessageDigest.getInstance("SHA-256")).equals(user.getPassword().split("\\$")[3])){
            return true;
        };
        return false;
    }
    public static String computePassword(String password, boolean formatted) throws NoSuchAlgorithmException {
        String salt = Algorithm.generateRandomString(16);
        if (formatted) {
            return "$SHA$" + salt + "$" +Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + salt, MessageDigest.getInstance("SHA-256"));
        }
        return Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + salt, MessageDigest.getInstance("SHA-256"));
    }
    public static class registry_verify_class {
        String username;
        String token;
        Long uid;
        Long expiration;

        public registry_verify_class(String username, String token, Long uid, Long expiration) {
            this.username = username;
            this.token = token;
            this.uid = uid;
            this.expiration = expiration;
        }
    }
}
