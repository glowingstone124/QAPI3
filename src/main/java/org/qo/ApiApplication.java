package org.qo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PostConstruct;
import org.qo.datas.DatabaseHealth;
import org.qo.datas.Nodes;
import org.qo.services.loginService.IPWhitelistServices;
import org.qo.services.loginService.Login;
import org.qo.services.loginService.RecentLoginService;
import org.qo.services.mmdb.Query;
import org.qo.services.proxyRelatedServices.ProxyRelatedImpl;
import org.qo.services.proxyRelatedServices.ProxyStatus;
import org.qo.services.registrationServices.RegistrationVerificationMethod;
import org.qo.services.registrationServices.RegistrationQuizProof;
import org.qo.services.registrationServices.RegistrationQuizService;
import org.qo.services.registrationServices.MinecraftRegistrationSessionService;
import org.qo.redis.Configuration;
import org.qo.services.messageServices.Msg;
import org.qo.services.gameStatusService.Status;
import org.qo.server.AvatarCache;
import org.qo.utils.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;

import java.io.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.qo.utils.UserProcess.*;

@RestController
@SpringBootApplication
public class ApiApplication {
    //public static String status = "no old status found";
    public static int serverAlive;
    public static long PackTime;
    public static int requests = 0;
    private final UAUtil ua;
    private final ReturnInterface ri;
    private final ProxyRelatedImpl proxyRelatedImpl;
    private final Status status;
    public Login login;
    private UserProcess userProcess;
    public IPWhitelistServices ipWhitelistServices;
    private final Nodes nodes;
    private final RegistrationQuizService registrationQuizService;
    private final MinecraftRegistrationSessionService minecraftRegistrationSessionService;
    private final boolean chambersEnabled;
    private final RecentLoginService recentLoginService;
    private final DatabaseHealth databaseHealth;

    @Autowired
    public ApiApplication(UAUtil uaUtil, ReturnInterface ri, Status status, Login login, IPWhitelistServices ipWhitelistServices, ProxyRelatedImpl proxyRelatedImpl, UserProcess userProcess, Nodes nodes, RegistrationQuizService registrationQuizService, MinecraftRegistrationSessionService minecraftRegistrationSessionService, RecentLoginService recentLoginService, DatabaseHealth databaseHealth, @Value("${qapi.registration.chambers-enabled:false}") boolean chambersEnabled) {
        this.ri = ri;
        this.ua = uaUtil;
        this.status = status;
        this.login = login;
        this.ipWhitelistServices = ipWhitelistServices;
        this.userProcess = userProcess;
        this.proxyRelatedImpl = proxyRelatedImpl;
        this.nodes = nodes;
        this.registrationQuizService = registrationQuizService;
        this.minecraftRegistrationSessionService = minecraftRegistrationSessionService;
        this.chambersEnabled = chambersEnabled;
        this.recentLoginService = recentLoginService;
        this.databaseHealth = databaseHealth;
    }

    @PostConstruct
    public void init() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::reqCount, 0, 1, TimeUnit.SECONDS);
    }

    private void reqCount() {
        if (requests > 100) {
            System.out.println("Total " + requests + " in one sec");
        }
        requests = 0;
    }

    @RequestMapping("/attac")
    public void test() {
        requests++;
    }

    @RequestMapping("/")
    public ResponseEntity<String> root() {
        JsonObject returnObj = new JsonObject();
        returnObj.addProperty("code", 0);
        returnObj.addProperty("build", Funcs.version);
        returnObj.addProperty("online", status.countOnline() + " server(s)");
        returnObj.addProperty("sql", databaseHealth.isAvailable());
        returnObj.addProperty("redis", Configuration.INSTANCE.getEnableRedis());
        returnObj.addProperty("proxies", proxyRelatedImpl.getProxies(ProxyStatus.ALIVE).size());
        return ri.GeneralHttpHeader(returnObj.toString());
    }

    @PostMapping("/qo/alive/upload")
    public ResponseEntity<Void> getAlive(@RequestBody String data, @RequestHeader(value = "Authorization", required = false) String header) {
        String token = AuthTokens.INSTANCE.resolve(null, header);
        if (token == null || nodes.getServerFromToken(token) < 0) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonObject Heartbeat = JsonParser.parseString(data).getAsJsonObject();
        PackTime = Heartbeat.get("timestamp").getAsLong();
        long currentTime = new Date().getTime();
        if (currentTime - PackTime > 3000) {
            serverAlive = -1;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String uselessthings = sdf.format(new Date(PackTime));
        switch (Heartbeat.get("stat").getAsInt()) {
            case 0 -> serverAlive = 0;
            //Ready
            case 1 -> {
                serverAlive = 1;

                Logger.log("Server Stopped at " + PackTime, Logger.LogLevel.INFO);
                Msg.Companion.putSys("服务器停止于" + uselessthings);
            }
            default -> serverAlive = -1;
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/qo/alive/download")
    public String queryAlive() {
        JsonObject aliveJSON = new JsonObject();
        aliveJSON.addProperty("stat", serverAlive);
        return aliveJSON.toString();
    }

    @PostMapping("/qo/upload/gametimerecord")
    public Mono<ResponseEntity<Void>> parser(@RequestParam(name = "name") String name, @RequestParam(name = "time") int time,
                                       @RequestHeader("Token") String token) {
        if (nodes.getServerFromToken(token) < 0) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        return userProcess.handleTime(name, time).thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/qo/download/getgametime")
    public Mono<ResponseEntity<String>> getTime(@RequestParam(name = "username") String username) {
        return userProcess.getTime(username).map(timeJson -> ri.GeneralHttpHeader(timeJson.toString()));
    }

    @GetMapping("/qo/download/logingreeting")
    public Mono<ResponseEntity<String>> loginGreeting(@RequestParam(name = "username") String username) {
        return userProcess.getTime(username).map(timeJson -> {
            JsonObject greetJson = new JsonObject();
            JsonArray onlines = new JsonArray();
            greetJson.add("time", timeJson);
            status.getStatusMap().forEach((id, info) -> {
                JsonArray singular_users = new JsonArray();
                info.get("players").getAsJsonArray().forEach((elem) -> singular_users.add(elem.getAsJsonObject().get("name")));
                JsonObject server_pair = new JsonObject();
                server_pair.addProperty("id", id);
                server_pair.add("players", singular_users);
                onlines.add(server_pair);
            });
            greetJson.add("online", onlines);
            return ri.GeneralHttpHeader(greetJson.toString());
        });
    }

    @RequestMapping("/app/latest")
    public ResponseEntity<String> update() {
        JsonObject returnObj = new JsonObject();
        returnObj.addProperty("version", 9);
        returnObj.addProperty("die", false);
        return ri.GeneralHttpHeader(returnObj.toString());
    }

    @RequestMapping("/qo/time")
    public ResponseEntity<String> timedate() {
        long timeStamp = System.currentTimeMillis();
        return ri.success(String.valueOf(timeStamp));
    }

    @PostMapping("/qo/upload/status")
    public void handlePost(@RequestBody String data, @RequestHeader("Authorization") String header) {
        status.upload(data, header);
    }

    @PostMapping("/qo/online")
    public Mono<ResponseEntity<Void>> handleOnlineRequest(@RequestParam String name, @RequestParam(required = false, defaultValue = "") String ip,
                                                          @RequestHeader("Token") String token) {
        if (nodes.getServerFromToken(token) < 0) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        return userProcess.recordPlayerOnline(name, ip)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/qo/offline")
    public ResponseEntity<Void> handleOffRequest(@RequestParam String name, @RequestHeader("Token") String token) {
        if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        handlePlayerOffline(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/qo/download/stats")
    public ResponseEntity<String> getServerStatistic() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(getServerStats(), headers, HttpStatus.OK);
    }

    @GetMapping("/qo/download/statpic")
    public ResponseEntity<Resource> handleStat() throws Exception {
        byte[] bar = Files.readAllBytes(Path.of("output.png"));
        Resource imageResource = new ByteArrayResource(bar);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return new ResponseEntity<>(imageResource, headers, HttpStatus.OK);
    }

    @GetMapping("/qo/download/status")
    public Mono<ResponseEntity<String>> returnStatus(Integer id) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        int statusId = (id == null) ? 1 : id;

        return status.downloadReactive(statusId)
                .map(statusJson -> new ResponseEntity<>(statusJson.toString(), headers, HttpStatus.OK));
    }

    /**
     * Inserts user data into the system.
     *
     * @param name     The name of the user. This parameter is required.
     * @param uid      The unique identifier for the user. This parameter is required.
     * @param password The password for the user. This parameter is required.
     * @param request  The HTTP servlet request. This is used to determine request details such as user agent.
     * @return A ResponseEntity containing a JSON response. If the request is identified as coming from a CLI tool,
     * the response will indicate failure with a HTTP status of BAD_REQUEST. Otherwise, the response will be processed
     * by the UserProcess.regMinecraftUser method.
     * @throws Exception If an error occurs during the processing of the user data.
     */
    @PostMapping(value = "/qo/upload/registry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> InsertData(@RequestBody RegisterRequest registration, ServerHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ua.isCLIToolRequest(request)) return Mono.just(new ResponseEntity<>("failed", headers, HttpStatus.BAD_REQUEST));
        RegistrationVerificationMethod verificationMethod =
                RegistrationVerificationMethod.parse(registration.verificationMethod());
        if (verificationMethod == null) {
            return Mono.just(ri.failed("invalid verification method"));
        }
        if (verificationMethod == RegistrationVerificationMethod.MINECRAFT) {
            if (!chambersEnabled) {
                JsonObject response = new JsonObject();
                response.addProperty("code", "minecraft_verification_unavailable");
                response.addProperty("message", "Chamber 世界测试暂未开放。");
                return Mono.just(ri.GeneralHttpHeader(response.toString(), HttpStatus.SERVICE_UNAVAILABLE));
            }
            boolean verified = minecraftRegistrationSessionService.consumePassed(
                    registration.verificationToken(),
                    registration.name(),
                    registration.uid()
            );
            if (!verified) {
                JsonObject response = new JsonObject();
                response.addProperty("code", "minecraft_verification_required");
                response.addProperty("message", "Minecraft 世界测试未通过、已过期或已使用。");
                return Mono.just(ri.GeneralHttpHeader(response.toString(), HttpStatus.FORBIDDEN));
            }
            return userProcess.regMinecraftUser(registration.name(), registration.uid(), request, registration.password(), 0);
        }
        RegistrationQuizProof proof = registrationQuizService.consumeProof(
                registration.verificationToken(),
                registration.name(),
                registration.uid()
        );
        if (proof == null) {
            JsonObject response = new JsonObject();
            response.addProperty("code", "quiz_verification_required");
            response.addProperty("message", "答题验证无效、已过期或已使用。");
            return Mono.just(ri.GeneralHttpHeader(response.toString(), HttpStatus.FORBIDDEN));
        }
        return userProcess.regMinecraftUser(registration.name(), registration.uid(), request, registration.password(), proof.getScore());
    }

    public record RegisterRequest(String name, Long uid, String password, String verificationMethod, String verificationToken) {}

    @PostMapping(value = "/qo/upload/confirmation", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> verifyReg(@RequestBody ConfirmationRequest confirmation,
                                            @RequestHeader("Authorization") String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String nodeToken = AuthTokens.INSTANCE.resolve(null, authorization);
        if (nodeToken == null || nodes.getServerFromToken(nodeToken) != 0) {
            JsonObject statObj = new JsonObject();
            statObj.addProperty("result", false);
            return Mono.just(new ResponseEntity<>(statObj.toString(), headers, HttpStatus.UNAUTHORIZED));
        }
        switch (confirmation.task()) {
            case 0:
                return userProcess.validateMinecraftUser(confirmation.token(), confirmation.uid())
                        .map(result -> {
                            JsonObject statObj = new JsonObject();
                            statObj.addProperty("result", result);
                            return new ResponseEntity<>(statObj.toString(), headers, HttpStatus.OK);
                        });
            case 1:
                return userProcess.validatePasswordUpdateRequest(confirmation.token(), confirmation.uid())
                        .map(result -> {
                            JsonObject passwordResult = new JsonObject();
                            passwordResult.addProperty("result", result);
                            return new ResponseEntity<>(passwordResult.toString(), headers, HttpStatus.OK);
                        });
            default:
                JsonObject defaultResult = new JsonObject();
                defaultResult.addProperty("result", false);
                return Mono.just(new ResponseEntity<>(defaultResult.toString(), headers, HttpStatus.OK));
        }
    }

    public record ConfirmationRequest(String token, Long uid, int task) {}

    @PostMapping(value = "/qo/upload/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> requestUpdatePassword(@RequestBody PasswordResetRequest reset) {
        return userProcess.updatePassword(reset.uid(), reset.password());
    }

    public record PasswordResetRequest(Long uid, String password) {}

    public Mono<ResponseEntity<String>> avartarTrans(String name) {
        return avatarResponse(name, null);
    }

    @RequestMapping("/qo/download/avatar")
    public Mono<ResponseEntity<String>> avartarTrans(@RequestParam() String name, ServerHttpRequest request) {
        return avatarResponse(name, avatarPublicBaseUrl(request));
    }

    static String avatarPublicBaseUrl(ServerHttpRequest request) {
        String authority = request.getURI().getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String scheme = request.getURI().getScheme();
        String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
        if (forwardedProto != null) {
            String candidate = forwardedProto.split(",", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (candidate.equals("http") || candidate.equals("https")) {
                scheme = candidate;
            }
        }
        return scheme + "://" + authority;
    }

    private Mono<ResponseEntity<String>> avatarResponse(String name, String publicBaseUrl) {
        if (name == null || name.isEmpty()) {
            return Mono.just(ri.GeneralHttpHeader("no input", HttpStatus.BAD_REQUEST));
        }
        if (!AvatarCache.isValidName(name)) {
            return Mono.just(ri.GeneralHttpHeader("invalid Minecraft username", HttpStatus.BAD_REQUEST));
        }
        return userProcess.avatarTrans(name, publicBaseUrl).map(ri::GeneralHttpHeader);
    }

    // Do not constrain content negotiation here.  The response always sets
    // image/png explicitly, while accepting a broad request Accept header
    // keeps image elements and API clients from receiving a 406 response.
    @GetMapping("/qo/download/avatar/image")
    public Mono<ResponseEntity<byte[]>> avatarImage(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String key) {
        if (key == null && !AvatarCache.isValidName(name)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        if (key != null && !AvatarCache.isValidCacheKey(key)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(() -> key == null ? AvatarCache.read(name) : AvatarCache.readKey(key))
                .subscribeOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    if (bytes == null) {
                        return ResponseEntity.notFound().build();
                    }
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.IMAGE_PNG);
                    headers.setCacheControl(CacheControl
                            .maxAge(1, TimeUnit.DAYS)
                            .cachePublic());
                    return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
                });
    }

    @RequestMapping("/qo/download/registry")
    public Mono<ResponseEntity<String>> GetData(@RequestParam String name) {
        return userProcess.queryReg(name).map(ri::GeneralHttpHeader);
    }

    @GetMapping("/qo/kotshi/player")
    public Mono<ResponseEntity<String>> queryKotshiPlayer(@RequestParam String name) {
        return userProcess.queryKotshiReg(name);
    }

    @GetMapping("/qo/webmsg/download")
    public ResponseEntity<String> returnWeb(@RequestHeader("Authorization") String authorization) {
        String token = AuthTokens.INSTANCE.resolve(null, authorization);
        if (token == null || nodes.getServerFromToken(token) < 0) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}");
        }
        return ri.GeneralHttpHeader(Msg.Companion.webGet());
    }

    @GetMapping("/qo/download/name")
    public Mono<ResponseEntity<String>> queryPlayerName(@RequestParam long qq) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return userProcess.queryReg(qq).map(body -> new ResponseEntity<>(body, headers, HttpStatus.OK));
    }

    @GetMapping("/qo/download/ip")
    public ResponseEntity<String> queryIp(@RequestParam String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>((String.valueOf(Query.INSTANCE.isCN(ip))), headers, HttpStatus.OK);
    }

    @GetMapping("/qo/download/ip/whitelisted")
    public Mono<ResponseEntity<String>> queryIpWhitelist(@RequestParam String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ipWhitelistServices.whitelistedWrapperReactive(ip)
                .map(body -> new ResponseEntity<>(body, headers, HttpStatus.OK));
    }

    @PostMapping(value = "/qo/game/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> login(@RequestBody LoginRequest credentials, ServerHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ua.isCLIToolRequest(request)) return Mono.just(new ResponseEntity<>("failed", headers, HttpStatus.BAD_REQUEST));
        if (credentials.username() == null || credentials.password() == null || credentials.password().length() > 128) {
            return Mono.just(new ResponseEntity<>("{\"result\":false}", headers, HttpStatus.BAD_REQUEST));
        }
        return userProcess.performLogin(credentials.username(), credentials.password(), credentials.ip(), Boolean.TRUE.equals(credentials.web()))
                .map(result -> {
                    if (result.getFirst() && !Boolean.TRUE.equals(credentials.web())) {
                        recentLoginService.recordSuccessfulLogin(credentials.username(), credentials.ip());
                    }
                    JsonObject retObj = new JsonObject();
                    retObj.addProperty("result", result.getFirst());
                    retObj.addProperty("token", result.getSecond());
                    return new ResponseEntity<>(retObj.toString(), headers, HttpStatus.OK);
                });
    }

    public record LoginRequest(String username, String password, String ip, Boolean web) {}

    @PostMapping("/qo/upload/loginattempt")
    public Mono<Void> handleLoginAttemptLogging(@RequestBody String data, @RequestParam(name = "auth", required = true) String auth) throws Exception {
        Funcs fc = new Funcs();
        if (fc.verify(auth, Funcs.Perms.FULL)) {
            return login.insertLoginLogReactive(data);
        }
        return Mono.empty();
    }

    @PostMapping("/qo/upload/explevel")
    public Mono<Void> handleExpLevelUpdate(@RequestParam String token, @RequestParam int lvl, @RequestParam String username) {
        if (nodes.getServerFromToken(token) != 1) {
            return Mono.empty();
        }

        return userProcess.updateLevel(username, lvl);
    }
}
