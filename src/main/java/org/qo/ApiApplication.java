package org.qo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
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
    public static String CreativeStatus;
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
    @PostMapping("/qo/apihook")
    public String webhook(@RequestBody String data) {
        System.out.println(data);
        return null;
    }
    @GetMapping("/forum/login")
    public String userLogin(@RequestParam(name="username", required = true)String username, @RequestParam(name = "password", required = true)String password , HttpServletRequest request) {
        return UserProcess.userLogin(username,password,request);
    }
    @JsonProperty("myinfo")
    @RequestMapping("/forum/fetch/myself")
    public String myinfo(@NotNull String name, HttpServletRequest request) throws Exception {
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
        String result = UserProcess.getTime(username).toString();
        return result;
    }
    @RequestMapping("/qo/query/resetpassword")
    public String resetPassword(String username, String hash, int deviceid, String newPassword, HttpServletRequest request) throws Exception {
        if (deviceid == 77560 && UserProcess.queryHash(hash).equals(username) && !Objects.equals(UserProcess.queryHash(hash), null)) {
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
        returnObj.put("version", 4);
        returnObj.put("die", false);
        return returnObj.toString();
    }
    @RequestMapping("/qo/download/systeminfo")
    public String systeminfo(){
        JSONObject systemInfoJson = new JSONObject();
        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        String cpuUsage = String.valueOf(operatingSystemMXBean.getSystemLoadAverage());
        systemInfoJson.put("cpu_usage", cpuUsage);
        JSONObject memoryUsageJson = getMemoryUsage();
        systemInfoJson.put("memory_usage", memoryUsageJson);
        JSONObject diskUsageJson = getDiskUsage();
        systemInfoJson.put("disk_usage", diskUsageJson);
        systemInfoJson.put("system_name", System.getProperty("os.name"));
        return systemInfoJson.toString();
    }
    private static JSONObject getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        JSONObject memoryUsageJson = new JSONObject();
        memoryUsageJson.put("total_memory", totalMemory);
        memoryUsageJson.put("used_memory", usedMemory);
        memoryUsageJson.put("free_memory", freeMemory);

        return memoryUsageJson;
    }
    private static JSONObject getDiskUsage() {
        File file = new File("/");
        long totalSpace = file.getTotalSpace();
        long freeSpace = file.getFreeSpace();
        long usableSpace = file.getUsableSpace();

        JSONObject diskUsageJson = new JSONObject();
        diskUsageJson.put("total_space", totalSpace);
        diskUsageJson.put("free_space", freeSpace);
        diskUsageJson.put("usable_space", usableSpace);

        return diskUsageJson;
    }
    @RequestMapping("/qo/time")
    public String timedate() {
        long timeStamp = System.currentTimeMillis();
        return ReturnInterface.success(String.valueOf(timeStamp));
    }
    @PostMapping("/qo/upload/status")
    public String handlePost(@RequestBody String data, HttpServletRequest request) {
        if (data != null) {
            status = data;
        }
        return null;
    }
    @RequestMapping("/forum/register")
    public String register(@RequestParam(name = "username", required = true) String username, @RequestParam(name = "password", required = true) String password, @RequestParam(name = "token", required = true) String token, HttpServletRequest request) throws Exception{
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
    public String returnStatus() {
        serverStatus = status;
        return serverStatus;
    }
    public static class UserInfo {
        public String username;
        public long uid;
        public int remain;

        public UserInfo(String username, long uid, int remain) {
            this.username = username;
            this.uid = uid;
            this.remain = remain;
        }
    }
    @RequestMapping("/qo/upload/registry")
    public static String InsertData(@RequestParam(name = "name", required = true)String name,@RequestParam(name = "uid", required = true) Long uid,@RequestParam(name = "appname", required = true) String appname, HttpServletRequest request) throws Exception {
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
    public static String link(String forum, String name){
        String output = UserProcess.Link(forum,name);
        return ReturnInterface.success(output);
    }
    @RequestMapping("/qo/download/link")
    public static String downloadLink(String name){
        if (UserProcess.queryLink(name) != null && !UserProcess.queryLink(name).equals("EMPTY")){
            return ReturnInterface.success(UserProcess.queryLink(name));
        } else {
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/qo/download/registry")
    public static String GetData(String name) throws Exception {
        return UserProcess.queryReg(name);
    }
    @PostMapping("/qo/economy/minus")
    public String minus(String username, int value){
        return ReturnInterface.success(operateEco(username,value, opEco.MINUS));
    }
    @PostMapping("/qo/economy/plus")
    public String add(String username, int value){
        return ReturnInterface.success(operateEco(username,value, opEco.ADD));
    }
    @PostMapping("/qo/msglist/upload")
    public void handleMsg(@RequestBody String data ,HttpServletRequest request){
        if (IPUtil.getIpAddr(request).equals("127.0.0.1")) {
            Msg.put(data);
        }
    }
    @GetMapping("/qo/msglist/download")
    public String returnMsg(){
        return Msg.get().toString();
    }
    @PostMapping("/qo/webmsg/upload")
    public void handleWeb(@RequestBody String content){
        Msg.webPut(content);
    }
    @GetMapping("/qo/webmsg/download")
    public String returnWeb(){
        return Msg.webGet();
    }
}
