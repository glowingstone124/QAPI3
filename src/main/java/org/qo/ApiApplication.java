package org.qo;

import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import org.qo.loginService.Login;
import org.qo.mmdb.Query;
import org.qo.redis.Configuration;
import org.qo.redis.Redis;
import org.qo.server.Nodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Date;

import static org.qo.Database.SQLAvliable;
import static org.qo.UserProcess.*;

@RestController
@SpringBootApplication
public class ApiApplication implements ErrorController {
    //public static String status = "no old status found";
    public static String serverStatus;
    public static int serverAlive;
    public static long PackTime;
    public static int requests = 0;
    private final Nodes nodes;
    private Funcs fc;
    private UAUtil ua;
    public SseService sseService;
    private final ReturnInterface ri;
    private Status status;
    public Login login;
    @Autowired
    public ApiApplication(SseService sseService, Funcs fc, UAUtil uaUtil, ReturnInterface ri, Status status, Nodes nodes, Login login) {
        this.sseService = sseService;
        this.fc = fc;
        this.ri = ri;
        this.ua = uaUtil;
        this.status = status;
        this.login = login;
        this.nodes = nodes;
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
        JSONObject returnObj = new JSONObject();
        returnObj.put("code", 0);
        returnObj.put("build", "202410141215");
        returnObj.put("online", status.countOnline() + " server(s)");
        returnObj.put("sql", SQLAvliable());
        returnObj.put("redis", Configuration.INSTANCE.getEnableRedis());
        return ri.GeneralHttpHeader(returnObj.toString());
    }

    @PostMapping("/qo/alive/upload")
    public void getAlive(@RequestBody String data, @RequestHeader("Authorization") String header) {
        JSONObject Heartbeat = new JSONObject(data);
        PackTime = Heartbeat.getLong("timestamp");
        long currentTime = new Date().getTime();
        if (currentTime - PackTime > 3000) {
            serverAlive = -1;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String uselessthings = sdf.format(new Date(PackTime));
        switch (Heartbeat.getInt("stat")) {
            case 0 -> serverAlive = 0;
            //Ready
            case 1 -> {
                serverAlive = 1;

                Logger.log("Server Stopped at " + PackTime, Logger.LogLevel.INFO);
                Msg.Companion.putSys("服务器停止于" + uselessthings);
            }
            default -> serverAlive = -1;
        }
    }

    @GetMapping("/qo/alive/download")
    public String queryAlive() {
        JSONObject aliveJSON = new JSONObject();
        aliveJSON.put("stat", serverAlive);
        return aliveJSON.toString();
    }

    @PostMapping("/qo/upload/gametimerecord")
    public void parser(@RequestParam(name = "name", required = true) String name, @RequestParam(name = "time", required = true) int time) {
        handleTime(name, time);
    }

    @GetMapping("/qo/download/getgametime")
    public ResponseEntity<String> getTime(@RequestParam(name = "username", required = true) String username) {
        return ri.GeneralHttpHeader(UserProcess.getTime(username).toString());
    }

    @RequestMapping("/app/latest")
    public ResponseEntity<String> update() {
        JSONObject returnObj = new JSONObject();
        returnObj.put("version", 9);
        returnObj.put("die", false);
        return ri.GeneralHttpHeader(returnObj.toString());
    }

    @RequestMapping("/qo/time")
    public ResponseEntity<String> timedate() {
        long timeStamp = System.currentTimeMillis();
        return ri.success(String.valueOf(timeStamp));
    }

    @PostMapping("/qo/upload/status")
    public void handlePost(@RequestBody String data, @RequestHeader("Authorization") String header) {
        //if (data != null) {
        //    status = data;
        //}
        status.upload(data, header);
    }

    @PostMapping("/qo/online")
    public void handleOnlineRequest(@RequestParam String name) {
        handlePlayerOnline(name);
    }

    @PostMapping("/qo/offline")
    public void handleOffRequest(@RequestParam String name) {
        handlePlayerOffline(name);
    }

    @GetMapping("/qo/download/stats")
    public ResponseEntity<String> getServerStatus() throws IOException {
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
    public ResponseEntity<String> returnStatus(Integer id) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        int statusId = (id == null) ? 1 : id;

        String statusJson = status.download(statusId).toString();

        return new ResponseEntity<>(statusJson, headers, HttpStatus.OK);
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
    @RequestMapping("/qo/upload/registry")
    public ResponseEntity<String> InsertData(@RequestParam(name = "name", required = true) String name, @RequestParam(name = "uid", required = true) Long uid, @RequestParam(name = "password", required = true) String password, HttpServletRequest request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ua.isCLIToolRequest(request)) return new ResponseEntity<>("failed", headers, HttpStatus.BAD_REQUEST);
        return regMinecraftUser(name, uid, request, password);
    }
    @RequestMapping("/qo/upload/confirmation")
    public static ResponseEntity<String> verifyReg(@RequestParam String token, HttpServletRequest request, @RequestParam Long uid, @RequestParam int task) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonObject statObj = new JsonObject();
        switch (task) {
            case 0:
                statObj.addProperty("result", validateMinecraftUser(token, request, uid));
                break;
            case 1:
                statObj.addProperty("result", validatePasswordUpdateRequest(token, uid));
                break;
            default:
                statObj.addProperty("result", false);
                break;
        }
        return new ResponseEntity<>(statObj.toString(), headers, HttpStatus.OK);
    }

    @PostMapping("/qo/upload/password")
    public static ResponseEntity<String> requestUpdatePassword(@RequestParam long uid, @RequestParam String password) throws ExecutionException, InterruptedException {
        return updatePassword(uid, password);
    }
    @RequestMapping("/qo/download/avatar")
    public ResponseEntity<String> avartarTrans(@RequestParam() String name) throws Exception {
        if (name == null || name.isEmpty()) {
            return ri.GeneralHttpHeader("no input");
        }
        return ri.GeneralHttpHeader(AvatarTrans(name));
    }

    @RequestMapping("/qo/download/registry")
    public ResponseEntity<String> GetData(@RequestParam(required = true) String name) {
        return ri.GeneralHttpHeader(queryReg(name));
    }

    @GetMapping("/qo/webmsg/download")
    public ResponseEntity<String> returnWeb() {
        return ri.GeneralHttpHeader(Msg.Companion.webGet());
    }

    @PostMapping("/qo/loginip/upload")
    public void handleLogin(@RequestParam(name = "ip", required = true) String ip, @RequestParam(name = "auth", required = true) String auth, @RequestParam(name = "username", required = true) String username) throws Exception {
        Funcs fc = new Funcs();
        if (fc.verify(auth, Funcs.Perms.FULL)) {
            insertLoginIP(ip, username);
        }
    }

    @GetMapping("/qo/loginip/download")
    public ResponseEntity<String> getLogin(@RequestParam(name = "username", required = true) String username) {
        String result = getLatestLoginIP(username);
        if (result.equals("undefined")) {
            return ri.denied("请求的用户没有历史ip记录");
        } else {
            return ri.success(result);
        }
    }


    @GetMapping("/qo/download/name")
    public ResponseEntity<String> queryPlayerName(@RequestParam long qq) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(queryReg(qq), headers, HttpStatus.OK);
    }

    @GetMapping("/qo/download/ip")
    public ResponseEntity<String> queryIp(@RequestParam String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>((String.valueOf(Query.INSTANCE.isCN(ip))), headers, HttpStatus.OK);
    }

    @GetMapping("/qo/game/login")
    public ResponseEntity<String> login(@RequestParam String username, @RequestParam String password, HttpServletRequest request) throws NoSuchAlgorithmException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ua.isCLIToolRequest(request)) return new ResponseEntity<>("failed", headers, HttpStatus.BAD_REQUEST);
        JsonObject retObj = new JsonObject();
        var result = performLogin(username, password);
        retObj.addProperty("result", result.getFirst());
        retObj.addProperty("token", result.getSecond());
        return new ResponseEntity<>(retObj.toString(), headers, HttpStatus.OK);
    }

    @PostMapping("/qo/upload/loginattempt")
    public void handleLoginAttemptLogging(@RequestBody String data, @RequestParam(name = "auth", required = true)String auth) throws Exception {
        Funcs fc = new Funcs();
        if (fc.verify(auth, Funcs.Perms.FULL)) {
            login.insertLoginLog(data);
        }
    }
}
