package org.qo.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import kotlin.Pair;
import kotlinx.coroutines.Dispatchers;
import org.qo.datas.ConnectionPool;
import org.qo.orm.AffiliatedAccountORM;
import org.qo.services.loginService.AffiliatedAccountServices;
import org.qo.services.loginService.AvatarRelatedImpl;
import org.qo.services.loginService.Login;
import org.qo.orm.UserORM;
import org.qo.datas.Mapping.*;
import org.qo.services.loginService.PlayerCardCustomizationImpl;
import org.qo.services.mail.Mail;
import org.qo.services.mail.MailPreset;
import org.qo.redis.DatabaseType;
import org.qo.redis.Redis;
import org.qo.server.AvatarCache;
import org.qo.services.messageServices.Msg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.io.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.qo.utils.Logger.LogLevel.*;

@Service
public class UserProcess {
    @Resource
    private PlayerCardCustomizationImpl playerCardCustomizationImpl;
    @Resource
    private AvatarRelatedImpl avatarRelatedImpl;
    @Resource
    private AffiliatedAccountServices affiliatedAccountServices;
    public static final String CODE_CONFIGURATION = "data/code.json";
    public static ConcurrentLinkedDeque<registry_verify_class> verify_list = new ConcurrentLinkedDeque<>();
    public static ConcurrentLinkedDeque<password_verify_class> pwdupd_list = new ConcurrentLinkedDeque<>();

    public static Request request = new Request();
    private static final ReturnInterface ri = new ReturnInterface();
    private static final Login login = new Login();
    public static UserORM userORM = new UserORM();
    private static CoroutineAdapter ca;
    private static final Redis redis = new Redis();

    private static final Map<String, ScheduledFuture<?>> onlineTasks = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService schedulerService = Executors.newScheduledThreadPool(4);

    @Autowired
    public UserProcess(CoroutineAdapter ca) {
        UserProcess.ca = ca;
    }

    public static String getServerStats() throws IOException {
        String jsonStr = Files.readString(Path.of("stat.json"));
        JsonArray statusArray = JsonParser.parseString(jsonStr).getAsJsonArray();

        for (JsonElement element : statusArray) {
            if (!element.isJsonObject()) {
                Logger.log("INVALID Status Message found.", ERROR);
                return null;
            }
            JsonObject event = element.getAsJsonObject();

            if (!hasValidField(event, "title") ||
                    !hasValidField(event, "date") ||
                    !hasValidField(event, "author") ||
                    !hasValidField(event, "summary")) {
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

    public static JsonObject getTime(String username) {
        JsonObject result = null;
        try (Connection connection = ConnectionPool.getConnection()) {
            result = new JsonObject();
            String query = "SELECT playtime FROM users WHERE username = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, username);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        int time = resultSet.getInt("playtime");
                        result.addProperty("name", username);
                        result.addProperty("time", time);
                    } else {
                        result.addProperty("error", -1);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            assert result != null;
            result.addProperty("error", e.getMessage());
        }

        return result;
    }

    public static String queryReg(String name) {
        JsonObject responseJson = new JsonObject();

        String redisKey = "users:" + name;
        int regDb = DatabaseType.QO_REG_DATABASE.getValue();

        if (Boolean.TRUE.equals(redis.exists(redisKey, regDb).ignoreException())) {
            String redisData = redis.get(name, regDb).ignoreException();
            JsonObject retObj = JsonParser.parseString(Objects.requireNonNull(redisData)).getAsJsonObject();
            retObj.addProperty("code", 0);
            return retObj.toString();
        }

        Users result = userORM.read(name);
        var service = SpringContextUtil.Companion.getCtx().getBean(AffiliatedAccountServices.class);
        if (result != null) {
            boolean temp = result.getTemp();
            Long uid = result.getUid();
            Boolean frozen = result.getFrozen();
            int eco = result.getEconomy();
            long playtime = result.getPlaytime();

            responseJson.addProperty("frozen", frozen);
            responseJson.addProperty("qq", uid);
            responseJson.addProperty("economy", eco);
            responseJson.addProperty("online", redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException());
            responseJson.addProperty("playtime", playtime);
            responseJson.addProperty("temp", temp);
            responseJson.addProperty("profile_id", result.getProfile_id());
            responseJson.addProperty("exp_level", result.getExp_level());
            responseJson.addProperty("score", result.getScore());
            responseJson.addProperty("affiliated", false);
            redis.insert("user:" + name, responseJson.toString(), regDb).ignoreException();

            responseJson.addProperty("code", temp ? 2 : 0);
            return responseJson.toString();
        } else if (service.validateAffiliatedAccount(name).getFirst()) {
            responseJson.addProperty("affiliated", true);
            responseJson.addProperty("host", service.validateAffiliatedAccount(name).getSecond().getHost());
        } else {
            responseJson.addProperty("code", 1);
            responseJson.addProperty("qq", -1);
        }
        return responseJson.toString();
    }

    public static String queryReg(long qq) {
        UserORM userORM = new UserORM();
        Users user = userORM.read(qq);
        JsonObject responseJson = new JsonObject();

        if (user != null) {
            String username = user.getUsername();
            Boolean frozen = user.getFrozen();
            int eco = Objects.requireNonNull(user.getEconomy());
            long playtime = Objects.requireNonNull(user.getPlaytime());

            responseJson.addProperty("code", 0);
            responseJson.addProperty("frozen", frozen);
            responseJson.addProperty("username", username);
            responseJson.addProperty("economy", eco);
            responseJson.addProperty("playtime", playtime);
            responseJson.addProperty("profile_id", user.getProfile_id());
        } else {
            responseJson.addProperty("code", 1);
            responseJson.addProperty("username", -1);
        }

        return responseJson.toString();
    }

    public static ResponseEntity<String> regMinecraftUser(String name, Long uid, HttpServletRequest request, String password, int score) throws ExecutionException, InterruptedException {
        CompletableFuture<ResponseEntity<String>> future = ca.run(() -> {
            if (userORM.read(name) != null) {
                return ri.failed("username already exist");
            }
            if (userORM.read(uid) != null) {
                return ri.failed("qq already exist");
            }
            if (Objects.equals(userORM.read(uid), null) && name != null && uid != null) {
                try {
                    userORM.create(new Users(name, uid, true, 3, 0, false, 0, false, 3, computePassword(password, true), UUID.randomUUID().toString(),0, score));
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
                    playerJson.addProperty("score", score);
                    redis.insert("user:" + name, playerJson.toString(), DatabaseType.QO_REG_DATABASE.getValue()).ignoreException();
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

    public String AvatarTrans(String name) throws Exception {
        CardProfile profileDetailWithGivenName = playerCardCustomizationImpl.getProfileDetailWithGivenName(name);
        JsonObject returnObject = new JsonObject();
        //Previous Logic: get corresponding avatar from mojang
        if (profileDetailWithGivenName == null || profileDetailWithGivenName.getAvatar().equals("default")) {
            String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
            String avatarURL = "https://playerdb.co/api/player/minecraft/";


            if (!AvatarCache.has(name)) {
                try {
                    String mojangJson = request.sendGetRequest(apiURL).get();
                    JsonObject uuidObj = JsonParser.parseString(mojangJson).getAsJsonObject();
                    String uuid = uuidObj.get("id").getAsString();

                    String playerDbJson = request.sendGetRequest(avatarURL + uuid)
                            .exceptionally(ex -> {
                                return null;
                            }).get();

                    if (playerDbJson == null) {
                        returnObject.addProperty("url", "https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7");
                        returnObject.addProperty("name", name);
                        return returnObject.toString();
                    }

                    JsonObject playerUUIDobj = JsonParser.parseString(playerDbJson).getAsJsonObject();

                    if (playerUUIDobj.get("success").getAsBoolean()) {
                        JsonObject player = playerUUIDobj
                                .getAsJsonObject("data")
                                .getAsJsonObject("player");

                        String avatar = player.get("avatar").getAsString();
                        String username = player.get("username").getAsString();

                        returnObject.addProperty("url", avatar);
                        returnObject.addProperty("name", username);
                        returnObject.addProperty("special", false);
                        AvatarCache.cache(avatar, username);
                        return returnObject.toString();
                    } else {
                        returnObject.addProperty("url", "https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7");
                        returnObject.addProperty("name", name);
                        returnObject.addProperty("special", false);
                        return returnObject.toString();
                    }

                } catch (Exception e) {
                    returnObject.addProperty("url", "https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7");
                    returnObject.addProperty("name", name);
                    returnObject.addProperty("special", false);
                    return returnObject.toString();
                }
            }

            returnObject.addProperty("url", "https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7");
            returnObject.addProperty("name", name);
            returnObject.addProperty("special", false);
            return returnObject.toString();
        } else {
            //New: get user-defined avatar from QO server
            var url = avatarRelatedImpl.getAvatarUrl(profileDetailWithGivenName.getAvatar());
            returnObject.addProperty("url", url);
            returnObject.addProperty("special", true);
            returnObject.addProperty("name", name);
            return returnObject.toString();
        }

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

    public static void handlePlayerOnline(String name, String ip) {
        if (Boolean.FALSE.equals(redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException())) {
            redis.insert("online" + name, "true", DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException();
            ScheduledFuture<?> future = schedulerService.scheduleAtFixedRate(()-> {
                redis.insert("login_history_" + name, ip, DatabaseType.QO_TEMP_DATABASE.getValue(), 60).ignoreException();
            }, 0, 40, TimeUnit.SECONDS);
            onlineTasks.put(name, future);
        }
    }

    public static void handlePlayerOffline(String name) {
        if (Boolean.TRUE.equals(redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException())) {
            redis.delete("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException();
            ScheduledFuture<?> future = onlineTasks.remove(name);
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    public static Pair<Boolean, String> performLogin(String username, String password, String ip, boolean web) throws NoSuchAlgorithmException {
        Users user = userORM.read(username);
        boolean tempFlag = false;
        Pair<Boolean, AffiliatedAccountServices.AffiliatedAccount> tempResult = null;
        if (user == null) {
            ApplicationContext ctx = SpringContextUtil.Companion.getCtx();
            AffiliatedAccountServices service = ctx.getBean(AffiliatedAccountServices.class);
            tempResult = service.validateAffiliatedAccount(username);
            if (!tempResult.getFirst()) {
                System.out.println("[DEBUG@performLogin,ORM]User " + username + " not found");
                return new Pair<>(false, null);
            } else {
                tempFlag = true;
            }
        }
        if (!tempFlag) {
            String[] passwordParts = user.getPassword().split("\\$");
            String user_salt = passwordParts[2];
            String user_hashed = passwordParts[3];
            String computedPasswordHash = Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + user_salt, MessageDigest.getInstance("SHA-256"));
            if (computedPasswordHash.equals(user_hashed)) {
                String token = login.generateToken(64);
                login.insertInto(token, username);
                if (!web && ip != null) {
                    redis.insert("login_history_" + username, ip, DatabaseType.QO_TEMP_DATABASE.getValue(), 60).ignoreException();
                }
                return new Pair<>(true, token);
            } else {
                redis.delete("login_history_" + username, DatabaseType.QO_TEMP_DATABASE.getValue()).ignoreException();
            }
            return new Pair<>(false, null);
        } else {
            String[] passwordParts = tempResult.getSecond().getPassword().split("\\$");
            String user_salt = passwordParts[2];
            String user_hashed = passwordParts[3];
            String computedPasswordHash = Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + user_salt, MessageDigest.getInstance("SHA-256"));
            if (computedPasswordHash.equals(user_hashed)) {
                return new Pair<>(true, "");
            }
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
    private static String toHex(byte[] bytes) {
        return Algorithm.toHex(bytes);
    }

    private static boolean hasValidField(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull();
    }
}
