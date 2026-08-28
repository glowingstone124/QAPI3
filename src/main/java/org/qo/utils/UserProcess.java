package org.qo.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.Resource;
import kotlin.Pair;
import org.qo.orm.AffiliatedAccountORM;
import org.qo.services.loginService.AffiliatedAccountServices;
import org.qo.services.loginService.AvatarRelatedImpl;
import org.qo.services.loginService.Login;
import org.qo.datas.Mapping.*;
import org.qo.services.loginService.PlayerCardCustomizationImpl;
import org.qo.services.playerStatistics.PlayerStatisticsService;
import org.qo.redis.DatabaseType;
import org.qo.redis.Redis;
import org.qo.server.AvatarCache;
import org.qo.services.messageServices.Msg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.io.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.qo.utils.Logger.LogLevel.*;

@Service
public class UserProcess {
    @Resource
    private PlayerCardCustomizationImpl playerCardCustomizationImpl;
    @Resource
    private AvatarRelatedImpl avatarRelatedImpl;
    @Resource
    private AffiliatedAccountServices affiliatedAccountServices;
    @Resource
    private PlayerStatisticsService playerStatisticsService;
    public static final String CODE_CONFIGURATION = "data/code.json";
    public static ConcurrentLinkedDeque<registry_verify_class> verify_list = new ConcurrentLinkedDeque<>();
    public static ConcurrentLinkedDeque<password_verify_class> pwdupd_list = new ConcurrentLinkedDeque<>();

    public static Request request = new Request();
    private static final ReturnInterface ri = new ReturnInterface();
    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private static final long VERIFICATION_TTL_MILLIS = TimeUnit.HOURS.toMillis(2);
    private static final Redis redis = new Redis();
    private final UserProcessReactiveStore reactiveStore;
    private final Login login;

    @Autowired
    public UserProcess(Login login, UserProcessReactiveStore reactiveStore) {
        this.login = login;
        this.reactiveStore = reactiveStore;
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

    public Mono<Void> handleTime(String name, int time) {
        if (time <= 0) {
            return Mono.empty();
        }
        return reactiveStore.incrementPlaytime(name, time)
                .doOnError(error -> Logger.log("experienced SQL exception while doing handleTime(): " + error.getMessage(), ERROR))
                .onErrorResume(error -> Mono.empty());
    }

    public Mono<JsonObject> getTime(String username) {
        return reactiveStore.getTime(username)
                .onErrorResume(error -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("error", error.getMessage());
                    return Mono.just(result);
                });
    }

    public Mono<String> queryReg(String name) {
        JsonObject responseJson = new JsonObject();

        String redisKey = "users:" + name;
        int regDb = DatabaseType.QO_REG_DATABASE.getValue();

        if (Boolean.TRUE.equals(redis.exists(redisKey, regDb).ignoreException())) {
            String redisData = redis.get(name, regDb).ignoreException();
            JsonObject retObj = JsonParser.parseString(Objects.requireNonNull(redisData)).getAsJsonObject();
            retObj.addProperty("code", 0);
            return Mono.just(retObj.toString());
        }

        return reactiveStore.readUser(name)
                .flatMap(result -> playerStatisticsService.getPlayerStatisticsJsonReactive(name).map(statistics -> {
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
                    responseJson.addProperty("last_login", result.getLast_login());
                    responseJson.addProperty("temp", temp);
                    responseJson.addProperty("profile_id", result.getProfile_id());
                    responseJson.addProperty("exp_level", result.getExp_level());
                    responseJson.addProperty("score", result.getScore());
                    responseJson.addProperty("affiliated", false);
                    responseJson.add("statistics", statistics);
                    redis.insert("user:" + name, responseJson.toString(), regDb).ignoreException();

                    responseJson.addProperty("code", temp ? 2 : 0);
                    return responseJson.toString();
                }))
                .switchIfEmpty(affiliatedAccountServices.validateAffiliatedAccountReactive(name)
                        .map(result -> {
                            if (result.getFirst() && result.getSecond() != null) {
                                responseJson.addProperty("affiliated", true);
                                responseJson.addProperty("host", result.getSecond().getHost());
                            } else {
                                responseJson.addProperty("code", 1);
                                responseJson.addProperty("qq", -1);
                            }
                            return responseJson.toString();
                        }));
    }

    public Mono<String> queryReg(long qq) {
        return reactiveStore.readUser(qq)
                .flatMap(user -> playerStatisticsService.getPlayerStatisticsJsonReactive(user.getUsername()).map(statistics -> {
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("code", 0);
                    responseJson.addProperty("frozen", user.getFrozen());
                    responseJson.addProperty("username", user.getUsername());
                    responseJson.addProperty("economy", user.getEconomy());
                    responseJson.addProperty("playtime", user.getPlaytime());
                    responseJson.addProperty("last_login", user.getLast_login());
                    responseJson.addProperty("profile_id", user.getProfile_id());
                    responseJson.add("statistics", statistics);
                    return responseJson.toString();
                }))
                .defaultIfEmpty("{\"code\":1,\"username\":-1}");
    }

    public Mono<ResponseEntity<String>> regMinecraftUser(String name, Long uid, ServerHttpRequest request, String password, int score) {
        if (name == null || !name.matches("^[A-Za-z0-9_]{3,16}$") || uid == null || uid <= 0
                || password == null || password.length() < 8 || password.length() > 128) {
            return Mono.just(ri.failed("invalid registration data"));
        }
        return hashPassword(password)
                .flatMap(encryptedPassword -> {
                    Users user = new Users(name, uid, true, 3, 0, false, 0, false, 3, encryptedPassword,
                            UUID.randomUUID().toString(), 0, score, 0L, 0L);
                    return reactiveStore.registerUser(user);
                })
                .publishOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if ("username_exists".equals(result)) return Mono.just(ri.failed("username already exist"));
                    if ("uid_exists".equals(result)) return Mono.just(ri.failed("qq already exist"));
                    if (!"created".equals(result)) return Mono.just(ri.failed("FAILED"));

                    String token = login.generateToken(32);
                    verify_list.removeIf(item -> Objects.equals(item.uid, uid));
                    verify_list.add(new registry_verify_class(name, digestToken(token), uid, System.currentTimeMillis()));
                    Msg.Companion.putSys("用户 " + uid + " 注册了账号 " + name
                            + "，请本人在 QQ 中于 2 小时内发送 .approve-register " + token);
                    JsonObject playerJson = new JsonObject();
                    playerJson.addProperty("qq", uid);
                    playerJson.addProperty("code", 0);
                    playerJson.addProperty("frozen", false);
                    playerJson.addProperty("pro", 0);
                    playerJson.addProperty("playtime", 0);
                    playerJson.addProperty("score", score);
                    redis.insert("user:" + name, playerJson.toString(), DatabaseType.QO_REG_DATABASE.getValue()).ignoreException();
                    Logger.log(name + " registered from " + IPUtil.getIpAddr(request), INFO);
                    return Mono.just(ri.success("Success!"));
                });
    }

    public Mono<ResponseEntity<String>> updatePassword(Long uid, String newPassword) {
        if (uid == null || uid <= 0 || newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            return Mono.just(ri.failed("invalid password"));
        }
        return reactiveStore.readUser(uid)
                .flatMap(user -> hashPassword(newPassword)
                        .map(encryptedPassword -> {
                            String token = login.generateToken(32);
                            pwdupd_list.removeIf(item -> Objects.equals(item.uid, uid));
                            pwdupd_list.add(new password_verify_class(encryptedPassword, digestToken(token), uid, System.currentTimeMillis()));
                            Msg.Companion.putSys("用户 " + uid + " 请求更改账号 " + user.getUsername()
                                    + " 的密码，请本人在 QQ 中于 2 小时内发送 .update-password " + token);
                            return ri.success("请求已提交。");
                        }))
                .defaultIfEmpty(ri.failed("用户不存在！"));
    }

    public Mono<Boolean> validatePasswordUpdateRequest(String token, Long uid) {
        pwdupd_list.removeIf(item -> System.currentTimeMillis() - item.createdAt >= VERIFICATION_TTL_MILLIS);
        if (uid == null || token == null) return Mono.just(false);
        for (password_verify_class classObj : pwdupd_list) {
            if (tokenMatches(classObj.tokenHash, token) && Objects.equals(classObj.uid, uid)
                    && pwdupd_list.remove(classObj)) {
                return reactiveStore.updatePassword(uid, classObj.passwordHash)
                        .onErrorReturn(false);
            }
        }
        return Mono.just(false);
    }

    public Mono<Void> updateLevel(String username, int level) {
        return reactiveStore.updateLevel(username, level)
                .doOnError(error -> Logger.log("failed to update exp_level for " + username + ": " + error.getMessage(), ERROR))
                .onErrorReturn(false)
                .then();
    }

    public Mono<Boolean> validateMinecraftUser(String token, Long uid) {
        Iterator<registry_verify_class> iterator = verify_list.iterator();
        while (iterator.hasNext()) {
            registry_verify_class item = iterator.next();
            if (tokenMatches(item.tokenHash, token) && Objects.equals(item.uid, uid)
                    && System.currentTimeMillis() - item.createdAt < VERIFICATION_TTL_MILLIS && verify_list.remove(item)) {
                return reactiveStore.unfreezeUser(uid)
                        .onErrorResume(error -> {
                            error.printStackTrace();
                            return Mono.just(false);
                        });
            } else if (System.currentTimeMillis() - item.createdAt >= VERIFICATION_TTL_MILLIS) {
                iterator.remove();
            }
        }
        return Mono.just(false);
    }

    public Mono<String> avatarTrans(String name) {
        return avatarTrans(name, null);
    }

    public Mono<String> avatarTrans(String name, String publicBaseUrl) {
        if (!AvatarCache.isValidName(name)) {
            return Mono.just(defaultAvatarJson(name));
        }
        return playerCardCustomizationImpl.getProfileDetailWithGivenNameReactive(name)
                .flatMap(profile -> {
                    if (profile.getAvatar() == null || "default".equals(profile.getAvatar())) {
                        return fetchMinecraftAvatar(name, publicBaseUrl);
                    }
                    return avatarRelatedImpl.getAvatarUrlReactive(profile.getAvatar())
                            .map(url -> {
                                JsonObject result = new JsonObject();
                                result.addProperty("url", url);
                                result.addProperty("special", true);
                                result.addProperty("name", name);
                                return result.toString();
                            })
                            .switchIfEmpty(Mono.just(avatarJson(null, name, true)));
                })
                .switchIfEmpty(fetchMinecraftAvatar(name, publicBaseUrl))
                .onErrorResume(error -> Mono.just(defaultAvatarJson(name)));
    }

    private Mono<String> fetchMinecraftAvatar(String name, String publicBaseUrl) {
        if (AvatarCache.isFresh(name)) {
            return Mono.just(avatarJson(AvatarCache.url(name, publicBaseUrl), name, false));
        }
        String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
        return Mono.fromFuture(request.sendGetRequest(apiURL))
                .map(JsonParser::parseString)
                .map(json -> json.getAsJsonObject().get("id").getAsString())
                .flatMap(uuid -> Mono.fromFuture(request.sendGetRequest("https://playerdb.co/api/player/minecraft/" + uuid))
                        .map(JsonParser::parseString)
                        .filter(JsonElement::isJsonObject)
                        .map(JsonElement::getAsJsonObject))
                .flatMap(playerDb -> {
                    if (!playerDb.get("success").getAsBoolean()) {
                        return Mono.just(defaultAvatarJson(name));
                    }
                    JsonObject player = playerDb.getAsJsonObject("data").getAsJsonObject("player");
                    String avatar = player.get("avatar").getAsString();
                    String username = player.get("username").getAsString();
                    AvatarLookup lookup = new AvatarLookup(avatar, username);
                    return Mono.fromFuture(AvatarCache.cacheAsync(lookup.url(), lookup.username()))
                        .map(ignored -> avatarJson(AvatarCache.url(lookup.username(), publicBaseUrl), lookup.username(), false))
                        .onErrorResume(error -> {
                            Logger.log("failed to cache avatar for " + lookup.username() + ": " + error.getMessage(), ERROR);
                            return Mono.just(avatarJson(lookup.url(), lookup.username(), false));
                        });
                })
                .onErrorResume(error -> Mono.just(defaultAvatarJson(name)));
    }

    private record AvatarLookup(String url, String username) {
    }

    private String defaultAvatarJson(String name) {
        return avatarJson("https://crafthead.net/avatar/8667ba71b85a4004af54457a9734eed7", name, false);
    }

    private String avatarJson(String url, String name, boolean special) {
        JsonObject result = new JsonObject();
        result.addProperty("url", url);
        result.addProperty("name", name);
        result.addProperty("special", special);
        return result.toString();
    }

    /**
     * @param username 查询用户名
     */
    @Deprecated
    public Mono<String> getLatestLoginIP(String username) {
        return reactiveStore.getLatestLoginIP(username)
                .onErrorResume(error -> {
                    error.printStackTrace();
                    return Mono.just("error");
                });
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
        }
    }

    public Mono<Void> recordPlayerOnline(String name, String ip) {
        handlePlayerOnline(name, ip);
        return reactiveStore.updateLastLogin(name, System.currentTimeMillis())
                .doOnError(error -> Logger.log("failed to update last_login for " + name + ": " + error.getMessage(), ERROR))
                .onErrorReturn(false)
                .then();
    }

    public static void handlePlayerOffline(String name) {
        if (Boolean.TRUE.equals(redis.exists("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException())) {
            redis.delete("online" + name, DatabaseType.QO_ONLINE_DATABASE.getValue()).ignoreException();
        }
    }

    public Mono<Pair<Boolean, String>> performLogin(String username, String password, String ip, boolean web) {
        return reactiveStore.readUser(username)
                .flatMap(user -> {
                    return passwordMatchesReactive(password, user.getPassword()).flatMap(matches -> {
                        if (!matches) {
                            return Mono.just(new Pair<Boolean, String>(false, null));
                        }
                        String token = login.generateToken(64);
                        Mono<Boolean> passwordUpgrade = user.getPassword().startsWith("$SHA$")
                                ? hashPassword(password).flatMap(hash -> reactiveStore.updatePassword(user.getUid(), hash))
                                : Mono.just(true);
                        return login.insertIntoReactive(token, username)
                                .then(passwordUpgrade
                                        .doOnError(error -> Logger.log("failed to upgrade password for " + username + ": " + error.getMessage(), ERROR))
                                        .onErrorReturn(false))
                                .then(web
                                        ? Mono.just(true)
                                        : reactiveStore.updateLastLogin(username, System.currentTimeMillis())
                                                .doOnError(error -> Logger.log("failed to update last_login for " + username + ": " + error.getMessage(), ERROR))
                                                .onErrorReturn(false))
                                .thenReturn(new Pair<>(true, token));
                    });
                })
                .switchIfEmpty(affiliatedAccountServices.validateAffiliatedAccountReactive(username)
                        .flatMap(result -> {
                            if (!result.getFirst() || result.getSecond() == null) {
                                return Mono.just(new Pair<Boolean, String>(false, null));
                            }
                            return passwordMatchesReactive(password, result.getSecond().getPassword())
                                    .map(matches -> matches
                                        ? new Pair<Boolean, String>(true, "")
                                        : new Pair<Boolean, String>(false, null));
                        }));
    }

    private Mono<String> hashPassword(String password) {
        return Mono.fromCallable(() -> computePassword(password, true))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> passwordMatchesReactive(String password, String encodedPassword) {
        return Mono.fromCallable(() -> passwordMatches(password, encodedPassword))
                .subscribeOn(Schedulers.boundedElastic());
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
        return passwordEncoder.encode(password);
    }

    private static boolean passwordMatches(String password, String encodedPassword) throws NoSuchAlgorithmException {
        if (encodedPassword == null) return false;
        if (encodedPassword.startsWith("$2")) return passwordEncoder.matches(password, encodedPassword);
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != 4 || !"SHA".equals(parts[1])) return false;
        String computed = Algorithm.hash(Algorithm.hash(password, MessageDigest.getInstance("SHA-256")) + parts[2], MessageDigest.getInstance("SHA-256"));
        return MessageDigest.isEqual(computed.getBytes(java.nio.charset.StandardCharsets.UTF_8), parts[3].getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String digestToken(String token) {
        try {
            return Algorithm.hash(token, MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean tokenMatches(String expectedDigest, String suppliedToken) {
        if (suppliedToken == null) return false;
        return MessageDigest.isEqual(expectedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                digestToken(suppliedToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static class registry_verify_class {
        String username;
        String tokenHash;
        Long uid;
        Long createdAt;

        public registry_verify_class(String username, String token, Long uid, Long expiration) {
            this.username = username;
            this.tokenHash = token;
            this.uid = uid;
            this.createdAt = expiration;
        }
    }

    public static class password_verify_class {
        String passwordHash;
        String tokenHash;
        Long uid;
        Long createdAt;

        public password_verify_class(String password, String token, Long uid, Long expiration) {
            this.passwordHash = password;
            this.tokenHash = token;
            this.uid = uid;
            this.createdAt = expiration;
        }
    }
    private static String toHex(byte[] bytes) {
        return Algorithm.toHex(bytes);
    }

    private static boolean hasValidField(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull();
    }
}
