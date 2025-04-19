package org.qo.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import kotlin.Pair;
import kotlinx.coroutines.Dispatchers;
import org.json.JSONArray;
import org.qo.datas.ConnectionPool;
import org.qo.services.loginService.Login;
import org.qo.orm.UserORM;
import org.qo.datas.Mapping.*;
import org.json.JSONObject;
import org.qo.services.mail.Mail;
import org.qo.services.mail.MailPreset;
import org.qo.redis.DatabaseType;
import org.qo.redis.Redis;
import org.qo.server.AvatarCache;
import org.qo.services.messageServices.Msg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;

import static org.qo.utils.Logger.LogLevel.*;

@Service
public class UserProcess {
    public static final String CODE_CONFIGURATION = "data/code.json";
    public static ConcurrentLinkedDeque<registry_verify_class> verify_list = new ConcurrentLinkedDeque<>();
    public static ConcurrentLinkedDeque<password_verify_class> pwdupd_list = new ConcurrentLinkedDeque<>();

    public static Request request = new Request();
    private static final ReturnInterface ri = new ReturnInterface();
    private static final Login login = new Login();
    public static UserORM userORM = new UserORM();
    private static CoroutineAdapter ca;
    private static final Redis redis = new Redis();

    @Autowired
    public UserProcess(CoroutineAdapter ca) {
        UserProcess.ca = ca;
    }

    public static String getServerStats() throws IOException {
        JSONArray statusArray = new JSONArray(Files.readString(Path.of("stat.json")));
        for (int i = 0; i < statusArray.length(); i++) {
            JSONObject event = statusArray.getJSONObject(i);
            String title = event.getString("title");
            String date = event.getString("date");
            String author = event.getString("author");
            String summary = event.getString("summary");
            if (summary == null || author == null || date == null || title == null) {
                Logger.log("INVALID Status Message found.", ERROR);
                return null;
            }
        }
        return statusArray.toString();
    }

    public static void handleTime(String name, int time) {
        if (time <= 0) {
            return;
        }
        ca.push(() -> {
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
                Logger.log("experienced SQL exception while doing handleTime(): " + e.getMessage(), ERROR);
            }
        }, Dispatchers.getIO());
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
        JSONObject responseJson = new JSONObject();
        if (redis.exists("users:" + name, DatabaseType.QO_REG_DATABASE.getValue())) {
            JsonObject retObj = (JsonObject) JsonParser.parseString(Objects.requireNonNull(redis.get(name, DatabaseType.QO_REG_DATABASE.getValue())));
            retObj.addProperty("code", 0);
            return retObj.toString();
        }
        Users result = userORM.read(name);
        if (result != null) {
            boolean temp = result.getTemp();
            Long uid = result.getUid();
            Boolean frozen = result.getFrozen();
            int eco = result.getEconomy();
            long playtime = result.getPlaytime();
            responseJson.put("frozen", frozen);
            responseJson.put("qq", uid);
            responseJson.put("economy", eco);
            responseJson.put("online", redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()));
            responseJson.put("playtime", playtime);
            responseJson.put("temp", temp);
            redis.insert("user:" + name, responseJson.toString(), DatabaseType.QO_REG_DATABASE.getValue());
            responseJson.put("code", 0);
            if (temp) responseJson.put("code", 2);
            return responseJson.toString();
        }
        responseJson.put("code", 1);
        responseJson.put("qq", -1);
        return responseJson.toString();
    }

    public static String queryReg(long qq) {
        UserORM userORM = new UserORM();
        Users user = userORM.read(qq);
        JSONObject responseJson = new JSONObject();
        if (user != null) {
            String username = user.getUsername();
            Boolean frozen = user.getFrozen();
            int eco = Objects.requireNonNull(user.getEconomy());
            long playtime = Objects.requireNonNull(user.getPlaytime());
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
        CompletableFuture<ResponseEntity<String>> future = ca.run(() -> {
            if (userORM.read(name) != null) {
                return ri.failed("username already exist");
            }
            if (userORM.read(uid) != null) {
                return ri.failed("qq already exist");
            }
            if (Objects.equals(userORM.read(uid), null) && name != null && uid != null) {
                try {
                    userORM.create(new Users(name, uid, true, 3, 0, false, 0, false, 0, computePassword(password, true)));
                    String token = Algorithm.generateRandomString(16);
                    Msg.Companion.putSys("用户 " + uid + "注册了一个账号：" + name + "，若非本人操作请忽略，确认账号请在消息发出后2小时内输入/approve-register " + token);
                    verify_list.add(new registry_verify_class(name, token, uid, System.currentTimeMillis()));
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
                ca.push(() -> {
                    JsonObject playerJson = new JsonObject();
                    playerJson.addProperty("qq", uid);
                    playerJson.addProperty("code", 0);
                    playerJson.addProperty("frozen", false);
                    playerJson.addProperty("pro", 0);
                    playerJson.addProperty("playtime", 0);
                    redis.insert("user:" + name, playerJson.toString(), DatabaseType.QO_REG_DATABASE.getValue());
                }, Dispatchers.getIO());
                Logger.log(name + " registered from " + IPUtil.getIpAddr(request), INFO);
                Mail mail = new Mail();
                mail.send(uid + "@qq.com", "感谢您注册QO2账号", MailPreset.register);
                return ri.success("Success!");
            } else {
                return ri.failed("FAILED");
            }
        }, Dispatchers.getIO());
        return future.get();
    }

    public static ResponseEntity<String> updatePassword(Long uid, String newPassword) throws ExecutionException, InterruptedException {
        CompletableFuture<ResponseEntity<String>> future = ca.run(() -> {
            Users user = userORM.read(uid);
            if (Objects.nonNull(user)) {
                String token = Algorithm.generateRandomString(16);
                Msg.Companion.putSys("用户 " + uid + "更改了账号：" + user.getUsername() + "的密码，若非本人操作请忽略，确认账号请在消息发出后2小时内输入/update-password " + token);
                pwdupd_list.add(new password_verify_class(newPassword, token, uid, System.currentTimeMillis()));
                return ri.success("请求已提交。");
            } else {
                return ri.failed("用户不存在！");
            }
        }, Dispatchers.getIO());
        return future.get();
    }

    private static void doUpdatePassword(Long uid, String newPassword) {
        try {
            String encryptedPassword = computePassword(newPassword, true);
            userORM.updatePassword(uid, encryptedPassword);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean validatePasswordUpdateRequest(String token, Long uid) {
        for (password_verify_class classObj : pwdupd_list) {
            if (Objects.equals(classObj.token, token) && Objects.equals(classObj.uid, uid) && System.currentTimeMillis() - classObj.expiration < 7200000) {
                doUpdatePassword(uid, classObj.password);
                return true;
            }
        }
        return false;
    }

    public static boolean validateMinecraftUser(String token, HttpServletRequest request, Long uid) {
        Iterator<registry_verify_class> iterator = verify_list.iterator();
        while (iterator.hasNext()) {
            registry_verify_class item = iterator.next();
            if (Objects.equals(item.token, token) && Objects.equals(item.uid, uid) && System.currentTimeMillis() - item.expiration < 7200000) {
                ca.push(() -> {
                    String sql = "UPDATE users SET frozen = false WHERE uid = ?";
                    try (Connection connection = ConnectionPool.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, uid);
                        statement.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }, Dispatchers.getIO());
                return true;
            } else if (System.currentTimeMillis() - item.expiration > 7200000) {
                iterator.remove();
                return false;
            }
        }
        return false;
    }

    public static String AvatarTrans(String name) throws Exception {
        String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
        String avatarURL = "https://playerdb.co/api/player/minecraft/";
        if (!AvatarCache.has(name)) {
            JSONObject uuidobj = new JSONObject(request.sendGetRequest(apiURL).get());
            String uuid = uuidobj.getString("id");
            JSONObject playerUUIDobj = new JSONObject(request.sendGetRequest(avatarURL + uuid).get());
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
     * @param username 查询用户名
     */
    @Deprecated
    public static String getLatestLoginIP(String username) {
        String query = "SELECT ip FROM loginip WHERE username = ?";
        try (Connection connection = ConnectionPool.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement(query)) {
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

    public enum opEco {
        ADD, SUB, MINUS
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

    public static void handlePlayerOnline(String name) {
        if (!redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue())) {
            redis.insert("online" + name, "true", DatabaseType.QO_ONLINE_DATABASE.getValue());
        }
    }

    public static void handlePlayerOffline(String name) {
        if (redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue())) {
            redis.delete("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue());
        }
    }

    public static Pair<Boolean, String> performLogin(String username, String password) throws NoSuchAlgorithmException {
        Users user = userORM.read(username);
        if (user == null) {
            System.out.println("[DEBUG@performLogin,ORM]User " + username + " not found");
            return new Pair<>(false, null);
        }

        String[] passwordParts = user.getPassword().split("\\$");
        String user_salt = passwordParts[2];

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        byte[] firstHash = sha256.digest(password.getBytes());
        sha256.reset();
        sha256.update(firstHash);
        sha256.update(user_salt.getBytes());
        String hashedPassword = Base64.getEncoder().encodeToString(sha256.digest());

        if (hashedPassword.equals(passwordParts[3])) {
            String token = login.generateToken(64);

            login.insertInto(token, username);
            return new Pair<>(true, token);
        }
        return new Pair<>(false, null);
    }

    /**
     * Computes a securely hashed password with an optional formatted prefix.
     *
     * @param password  The plaintext password to be hashed.
     * @param formatted Flag indicating if the result should include the "$SHA$<salt>$" prefix.
     * @return The hashed password, optionally formatted with a prefix.
     * @throws NoSuchAlgorithmException If the SHA-256 algorithm is not available.
     */
    public static String computePassword(String password, boolean formatted) throws NoSuchAlgorithmException {
        String salt = Algorithm.generateRandomString(16);
        if (formatted) {
            return "$SHA$" + salt + "$" + Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + salt, MessageDigest.getInstance("SHA-256"));
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

    public static class password_verify_class {
        String password;
        String token;
        Long uid;
        Long expiration;

        public password_verify_class(String password, String token, Long uid, Long expiration) {
            this.password = password;
            this.token = token;
            this.uid = uid;
            this.expiration = expiration;
        }
    }
}
