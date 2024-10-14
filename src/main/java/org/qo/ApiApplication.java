package org.qo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.coyote.Response;
import org.json.JSONObject;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Date;

import static org.qo.UserProcess.*;

@RestController
@SpringBootApplication
public class ApiApplication implements ErrorController {
    //public static String status = "no old status found";
    public static String serverStatus;
    public static int serverAlive;
    public static long PackTime;
    public static int requests = 0;
    private Funcs fc;
    private UAUtil ua;
    public SseService sseService;
    private ReturnInterface ri;
    private Status status;
    @Autowired
    public ApiApplication(SseService sseService, Funcs fc, UAUtil uaUtil, ReturnInterface ri, Status status) {
        this.sseService = sseService;
        this.fc = fc;
        this.ri = ri;
        this.ua = uaUtil;
        this.status = status;
    }
    @PostConstruct
    public void init() {
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
    public ResponseEntity<String> root() {
        JSONObject returnObj = new JSONObject();
        returnObj.put("code",0);
        returnObj.put("build", "202409052002");
        return ri.GeneralHttpHeader(returnObj.toString());
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
                Msg.Companion.put("服务器停止于"+ uselessthings);
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
    @PostMapping("/qo/upload/gametimerecord")
    public void parser(@RequestParam(name = "name", required = true) String name,@RequestParam(name = "time", required = true) int time){
        UserProcess.handleTime(name,time);
    }
    @GetMapping("/qo/download/getgametime")
    public ResponseEntity<String> getTime(@RequestParam(name = "username", required = true)String username) {
        return ri.GeneralHttpHeader(UserProcess.getTime(username).toString());
    }
    @RequestMapping("/app/latest")
    public ResponseEntity<String> update(){
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
    public void handlePost(@RequestBody String data) {
        //if (data != null) {
        //    status = data;
        //}
        status.upload(data);
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
    @GetMapping("/qo/download/status")
    public ResponseEntity<String> returnStatus() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        /*if (status.equals("no old status found")) {
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
         */
        return new ResponseEntity<>(status.download().toString(), headers, HttpStatus.OK);
    }

    /**
     * Inserts user data into the system.
     *
     * @param name The name of the user. This parameter is required.
     * @param uid The unique identifier for the user. This parameter is required.
     * @param password The password for the user. This parameter is required.
     * @param request The HTTP servlet request. This is used to determine request details such as user agent.
     *
     * @return A ResponseEntity containing a JSON response. If the request is identified as coming from a CLI tool,
     * the response will indicate failure with a HTTP status of BAD_REQUEST. Otherwise, the response will be processed
     * by the UserProcess.regMinecraftUser method.
     *
     * @throws Exception If an error occurs during the processing of the user data.
     */
    @RequestMapping("/qo/upload/registry")
    public ResponseEntity<String> InsertData(@RequestParam(name = "name", required = true)String name,@RequestParam(name = "uid", required = true) Long uid, @RequestParam(name = "password", required = true) String password, HttpServletRequest request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ua.isCLIToolRequest(request)) return new ResponseEntity<>( "failed", headers,HttpStatus.BAD_REQUEST);
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
    @RequestMapping("/qo/download/avatar")
    public ResponseEntity<String> avartarTrans(@RequestParam() String name) throws Exception {
        return ri.GeneralHttpHeader(UserProcess.AvatarTrans(name));
    }
    @RequestMapping("/qo/download/registry")
    public ResponseEntity<String> GetData(String name){
        return ri.GeneralHttpHeader(UserProcess.queryReg(name));
    }
    @GetMapping("/qo/webmsg/download")
    public ResponseEntity<String> returnWeb(){
        return ri.GeneralHttpHeader(Msg.Companion.webGet());
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
            return ri.denied("请求的用户没有历史ip记录");
        } else {
            return ri.success(result);
        }
    }
    @GetMapping("/qo/inventory/request")
    public ResponseEntity<String> insertInventoryInspection(@RequestParam String name, @RequestParam String from){
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String key = Funcs.generateRandomString(32);
        JsonObject retObj = new JsonObject();
        if (UserProcess.insertInventoryViewRequest(name,from,key)){
            Msg.Companion.put(from+"发起了一个新的物品栏访问请求到"+name+ "。如果批准请输入/approve + 32位密钥");
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
    public ResponseEntity<String> login(@RequestParam String username, @RequestParam String password, HttpServletRequest request) throws NoSuchAlgorithmException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ua.isCLIToolRequest(request)) return new ResponseEntity<>( "failed", headers,HttpStatus.BAD_REQUEST);
        JsonObject retObj = new JsonObject();
        retObj.addProperty("result", verifyPasswd(username, password));
        return new ResponseEntity<>(retObj.toString(), headers, HttpStatus.OK);
    }
}
