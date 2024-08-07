package org.qo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.User;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.qo.redis.Operation;
import org.qo.server.Documents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import java.util.*;

import static org.qo.Algorithm.*;
import static org.qo.UserProcess.*;

@RestController
@SpringBootApplication
public class ApiApplication implements ErrorController {
    public static String status = "no old status found";
    public static String serverStatus;
    public static int serverAlive;
    public static long PackTime;
    public static int requests = 0;
    private Funcs fc;
    public SseService sseService;
    @Autowired
    public ApiApplication(SseService sseService, Funcs fc) {
        this.sseService = sseService;
        this.fc = fc;
    }
    @PostConstruct
    public void init(){
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::reqCount, 0, 1, TimeUnit.SECONDS);
    }
    private void reqCount() {
        if (requests>100){
            System.out.println("Total "+requests+" in one sec");
        }
        requests = 0;
    }
    @RequestMapping("/attac")
    public void test(){
        requests++;
    }
    @RequestMapping("/")
    public String root() {
        JSONObject returnObj = new JSONObject();
        returnObj.put("code",0);
        returnObj.put("build", "202402241454");
        return returnObj.toString();
    }
    @RequestMapping("/error")
    public String error(HttpServletResponse response){
        JSONObject returnObj = new JSONObject();
        long timeStamp = System.currentTimeMillis();
        returnObj.put("timestamp", timeStamp);
        returnObj.put("error", response.getStatus());
        returnObj.put("code", -1);
        return returnObj.toString();
    }
    @PostMapping("/qo/upload/github")
    public void HandleGithub(@RequestBody String req){
        JSONObject body = new JSONObject(req);
    }
    @PostMapping("/qo/apihook")
    public String webhook(@RequestBody String data) {
        System.out.println(data);
        return null;
    }
    @GetMapping("/forum/login")
    public ResponseEntity<String> userLogin(@RequestParam(name="username", required = true)String username, @RequestParam(name = "password", required = true)String password , HttpServletRequest request) {
        return UserProcess.userLogin(username,password,request);
    }
    @JsonProperty("myinfo")
    @RequestMapping("/forum/fetch/myself")
    public String myinfo(@RequestParam String name, HttpServletRequest request) {
        return UserProcess.fetchMyinfo(name,request);
    }

    @PostMapping("/qo/alive/upload")
    public void getAlive(@RequestBody String data){
        JSONObject Heartbeat = new JSONObject(data);
        PackTime = Heartbeat.getLong("timestamp");
        long currentTime = new Date().getTime();
        if (currentTime - PackTime > 3000){
            serverAlive = -1;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String uselessthings = sdf.format(new Date(PackTime));
        switch (Heartbeat.getInt("stat")){
            case 0 -> serverAlive = 0;
            //Ready
            case 1 -> {
                serverAlive = 1;

                Logger.log("Server Stopped at "+ PackTime, Logger.LogLevel.INFO);
                Msg.put("服务器停止于"+ uselessthings);
            }
            default -> serverAlive = -1;
        }
    }
    @GetMapping("/qo/alive/download")
    public String queryAlive(){
        JSONObject aliveJSON = new JSONObject();
        aliveJSON.put("stat", serverAlive);
        return aliveJSON.toString();
    }
    @RequestMapping("/api/isFirstLogin")
    public String firstLogin(@RequestParam(name = "name", required = true) String name, HttpServletRequest request){
        return UserProcess.firstLoginSearch(name,request);
    }

    @PostMapping("/qo/upload/gametimerecord")
    public void parser(@RequestParam(name = "name", required = true) String name,@RequestParam(name = "time", required = true) int time){
        UserProcess.handleTime(name,time);
    }
    @GetMapping("/qo/download/getgametime")
    public String getTime(@RequestParam(name = "username", required = true)String username) {
        return UserProcess.getTime(username).toString();
    }
    @RequestMapping("/qo/query/resetpassword")
    public ResponseEntity<String> resetPassword(String username, String hash, int deviceid, String newPassword, HttpServletRequest request) throws Exception {
        if (deviceid == 77560 && Objects.equals(UserProcess.queryHash(hash), username) && !Objects.equals(UserProcess.queryHash(hash), null)) {
            if (UserProcess.changeHash(username, hashSHA256(newPassword))) {
                Logger.log("[PASSWORD] ip " + IPUtil.getIpAddr(request) + " queried resetPassword and changed username " + username + "'s password.",Logger.LogLevel.INFO);
                return ReturnInterface.success("SUCCESS");
            }
        } else if(deviceid != 77560) {
            return ReturnInterface.failed("Deviceid Mismatched");
        } else if(!Objects.equals(UserProcess.queryHash(hash), username)){
            return ReturnInterface.failed("Network Err");
        }
        Logger.log("ip " + IPUtil.getIpAddr(request) + " queried resetPassword and wanted to change username " + username + "'s password. but unsuccessful", Logger.LogLevel.INFO);
        return  ReturnInterface.failed("FAILED");
    }
    @RequestMapping("/app/latest")
    public String update(){
        JSONObject returnObj = new JSONObject();
        returnObj.put("version", 9);
        returnObj.put("die", false);
        return returnObj.toString();
    }
    @RequestMapping("/qo/time")
    public ResponseEntity<String> timedate() {
        long timeStamp = System.currentTimeMillis();
        return ReturnInterface.success(String.valueOf(timeStamp));
    }
    @PostMapping("/qo/upload/status")
    public String handlePost(@RequestBody String data) {
        if (data != null) {
            status = data;
        }
        return null;
    }
    @PostMapping("/qo/online")
    public void handleOnlineRequest(@RequestParam String name){
        UserProcess.handlePlayerOnline(name);
    }
    @PostMapping("/qo/offline")
    public void handleOffRequest(@RequestParam String name){
        UserProcess.handlePlayerOffline(name);
    }
    @GetMapping("/qo/download/stats")
    public ResponseEntity<String> getServerStatus() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(UserProcess.getServerStats(), headers, HttpStatus.OK);
    }
    @GetMapping("/qo/download/statpic")
    public ResponseEntity<Resource> handleStat() throws Exception {
        byte[] bar = Files.readAllBytes(Path.of("output.png"));
        Resource imageResource = new ByteArrayResource(bar);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return new ResponseEntity<>(imageResource, headers, HttpStatus.OK);
    }
    @RequestMapping("/forum/register")
    public ResponseEntity<String> register(@RequestParam(name = "username", required = true) String username, @RequestParam(name = "password", required = true) String password, @RequestParam(name = "token", required = true) String token, HttpServletRequest request) throws Exception{
        if(Objects.equals(token, token(username,1700435)) && !UserProcess.queryForum(username)){
            try {
                UserProcess.regforum(username, password);
            } catch (NullPointerException e){
                e.printStackTrace();
            } catch (Exception ex){
                ex.printStackTrace();
            }
            Logger.log(IPUtil.getIpAddr(request) + "[register]" + username + " registered.", Logger.LogLevel.INFO);
            return ReturnInterface.success("SUCCESS");
        }
        Logger.log(IPUtil.getIpAddr(request) + "[register]" + username + " registered but failed.", Logger.LogLevel.INFO);
        return ReturnInterface.denied("FAILED");
    }
    @GetMapping("/qo/download/status")
    public ResponseEntity<String> returnStatus() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (status.equals("no old status found")) {
            JSONObject plObj = new JSONObject();
            plObj.put("code", 1);
            plObj.put("reason", "no old status found");
            return new ResponseEntity<>(plObj.toString(), headers, HttpStatus.OK);
        } else {
            JSONObject statObj = new JSONObject(status);
            if (System.currentTimeMillis() - statObj.getLong("timestamp") >= 3000L) {
                statObj.put("code", 1);
                statObj.put("reason", "status expired: latest data was presented longer than 3000 milliseconds ago.");
            } else {
                statObj.put("code", 0);
            }
            return new ResponseEntity<>(statObj.toString(), headers, HttpStatus.OK);
        }
    }

    @RequestMapping("/qo/upload/registry")
    public static ResponseEntity<String> InsertData(@RequestParam(name = "name", required = true)String name,@RequestParam(name = "uid", required = true) Long uid, @RequestParam(name = "password", required = true) String password, HttpServletRequest request) throws Exception {
        return UserProcess.regMinecraftUser(name, uid, request, password);
    }
    @RequestMapping("/qo/upload/confirmation")
    public static ResponseEntity<String> verifyReg(@RequestParam String token, HttpServletRequest request,@RequestParam Long uid) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonObject statObj = new JsonObject();
        statObj.addProperty("result", UserProcess.validateMinecraftUser(token,request,uid));
        return new ResponseEntity<>(statObj.toString(), headers, HttpStatus.OK);
    }
    @RequestMapping("/qo/download/memorial")
    public static String downloadMemorial() throws IOException {
        return UserProcess.downloadMemorial();
    }
    @RequestMapping("/qo/download/avatar")
    public String avartarTrans(@RequestParam() String name) throws Exception {
        return UserProcess.AvatarTrans(name);
    }
    @PostMapping("/qo/delete/user")
    public ResponseEntity<String> exec(@RequestParam String appname, @RequestParam String minecraftUser) throws ExecutionException, InterruptedException {
        return UserProcess.delMinecraftUser(appname,minecraftUser);
    }
    @RequestMapping("/qo/upload/link")
    public ResponseEntity<String> link(String forum, String name){
        String output = UserProcess.Link(forum,name);
        return ReturnInterface.success(output);
    }
    @RequestMapping("/qo/download/link")
    public ResponseEntity<String> downloadLink(String name){
        if (UserProcess.queryLink(name) != null && !Objects.equals(UserProcess.queryLink(name), "EMPTY")){
            return ReturnInterface.success(UserProcess.queryLink(name));
        } else {
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/qo/download/registry")
    public static String GetData(String name){
        return UserProcess.queryReg(name);
    }
    @PostMapping("/qo/msglist/upload")
    public void handleMsg(@RequestBody String data,@RequestParam(name="auth",required = true) String auth) throws Exception {
        Funcs fc = new Funcs();
        if (fc.verify(auth, Funcs.Perms.CHATSYNC)) {
            Msg.put(data);
        }
    }
    @GetMapping("/qo/msglist/download")
    public String returnMsg(){
        return Msg.get().toString();
    }
    @GetMapping("/qo/webmsg/download")
    public String returnWeb(){
        return Msg.webGet();
    }
    @PostMapping("/qo/loginip/upload")
    public void handleLogin(@RequestParam(name = "ip", required = true) String ip,@RequestParam(name = "auth", required = true) String auth, @RequestParam(name="username",required = true) String username) throws Exception {
        Funcs fc = new Funcs();
        if (fc.verify(auth, Funcs.Perms.FULL)) {
            insertLoginIP(ip, username);
        }
    }
    @GetMapping("/qo/loginip/download")
    public ResponseEntity<String> getLogin(@RequestParam(name="username",required = true) String username){
        String result = getLatestLoginIP(username);
        if (result.equals("undefined")){
            return ReturnInterface.denied("请求的用户没有历史ip记录");
        } else {
            return ReturnInterface.success(result);
        }
    }
    @GetMapping("/qo/inventory/request")
    public ResponseEntity<String> insertInventoryInspection(@RequestParam String name, @RequestParam String from){
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String key = Funcs.generateRandomString(32);
        JsonObject retObj = new JsonObject();
        if (UserProcess.insertInventoryViewRequest(name,from,key)){
            Msg.put(from+"发起了一个新的物品栏访问请求到"+name+ "。如果批准请输入/approve + 32位密钥");
            retObj.addProperty("key", key);
            retObj.addProperty("code", 0);
        } else {
            retObj.addProperty("code", 1);
        }
        return new ResponseEntity<>(retObj.toString(), headers, HttpStatus.OK);
    }
    @GetMapping("/qo/inventory/validate")
    public void validateInventoryView(@RequestParam String auth, @RequestParam String key) throws Exception {
        if (fc.verify(auth, Funcs.Perms.FULL)){
            UserProcess.approveInventoryViewRequest(key);
        }
    }
    @GetMapping("/qo/inventory/query")
    public ResponseEntity<String> queryInventoryStat(@RequestParam String secrets){
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(UserProcess.InventoryViewStatus(secrets), headers, HttpStatus.OK);
    }
    @GetMapping("/qo/download/name")
    public ResponseEntity<String> queryPlayerName(@RequestParam long qq){
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(UserProcess.queryReg(qq), headers, HttpStatus.OK);
    }
    @GetMapping("/qo/game/login")
    public ResponseEntity<String> login(@RequestParam String username, @RequestParam String password) throws NoSuchAlgorithmException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonObject retObj = new JsonObject();
        retObj.addProperty("result", verifyPasswd(username, password));
        return new ResponseEntity<>(retObj.toString(), headers, HttpStatus.OK);
    }
}
