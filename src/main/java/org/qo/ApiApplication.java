package org.qo;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.qo.server.Documents;
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
    public static String status;
    public static String serverStatus;
    public static int serverAlive;
    public static long PackTime;
    public static int requests = 0;

    public ApiApplication(){
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
    @PostMapping("/qo/upload/paste")
    public String paste(@RequestBody String text, HttpServletRequest request) throws Exception{
        Paste ps = new Paste();
        return ps.handle(request, text);
    }
    @GetMapping("/qo/paste/{route}")
    public String getContent(@PathVariable String route) throws Exception{
        Paste ps = new Paste();
        return ps.getContent(route);
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
    public String myinfo(@NotNull String name, HttpServletRequest request) {
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
        switch (Heartbeat.getInt("stat")){
            case 0 -> serverAlive = 0;
            //Ready
            case 1 -> {
                serverAlive = 1;
                Logger.log("Server Stopped at"+ PackTime, Logger.LogLevel.INFO);
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
        returnObj.put("version", 6);
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
        serverStatus = status;
        return new ResponseEntity<>(serverStatus,  headers, HttpStatus.OK);
    }
    @RequestMapping("/qo/upload/registry")
    public static ResponseEntity<String> InsertData(@RequestParam(name = "name", required = true)String name,@RequestParam(name = "uid", required = true) Long uid,@RequestParam(name = "appname", required = true) String appname, HttpServletRequest request) throws Exception {
        return UserProcess.regMinecraftUser(name, uid, request, appname);
    }
    @RequestMapping("/qo/download/memorial")
    public static String downloadMemorial() throws IOException {
        return UserProcess.downloadMemorial();
    }
    @RequestMapping("/qo/download/avatar")
    public String avartarTrans(@RequestParam() String name) throws Exception {
        return UserProcess.AvatarTrans(name);
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
    @GetMapping("/qo/document/{route}")
    public String document(@PathVariable String route) throws Exception{
        Documents dc = new Documents();
        if (route.equals("index")){
             return dc.lists();
        } else {
            return dc.transform(route);
        }
    }
    @RequestMapping("/qo/download/registry")
    public static String GetData(String name){
        return UserProcess.queryReg(name);
    }
    @PostMapping("/qo/economy/minus")
    public ResponseEntity<String> minus(String username, int value){
        return ReturnInterface.success(operateEco(username,value, opEco.MINUS));
    }
    @PostMapping("/qo/economy/plus")
    public ResponseEntity<String> add(String username, int value){
        return ReturnInterface.success(operateEco(username,value, opEco.ADD));
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
}
