package org.qo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.User;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    Path filePath = Paths.get("app/latest/QCommunity-3.0.3-Setup.exe");
    Path webdlPath = Paths.get("webs/download.html");
    public static String jdbcUrl = getDatabaseInfo("url");
    public static String sqlusername = getDatabaseInfo("username");
    public static String sqlpassword = getDatabaseInfo("password");

    public static Map<String, Integer> SurvivalMsgList = new HashMap<String, Integer>();
    public static Map<String, Integer> CreativeMsgList = new HashMap<String, Integer>();
    String jsonData = "data/playermap.json";
    public static String noticeData = "data/notice.json";

    public ApiApplication() throws IOException {
    }

    @RequestMapping("/")
    public String root() {
        JSONObject returnObj = new JSONObject();
        returnObj.put("code",0);
        returnObj.put("build", "202312081719");
        return returnObj.toString();
    }
    @RequestMapping("/error")
    public String error(HttpServletRequest request, HttpServletResponse response){
        JSONObject returnObj = new JSONObject();
        long timeStamp = System.currentTimeMillis();
        returnObj.put("timestamp", timeStamp);
        returnObj.put("error", response.getStatus());
        returnObj.put("code", -1);
        return returnObj.toString();
    }
    @RequestMapping("/introduction")
    public String introductionMenu() {
        try {
            if ((Files.readString(Path.of("forum/introduction/main.json"), StandardCharsets.UTF_8) != null)) {
                return Files.readString(Path.of("forum/introduction/main.json"));
            }
        } catch (IOException e) {
            return ReturnInterface.failed("ERROR:CONFIGURATION NOT FOUND");
        }
        return ReturnInterface.failed("ERROR");
    }

    @RequestMapping("/introduction/server")
    public String serverIntros(@RequestParam(name = "articleID", required = true) int articleID) throws Exception {
        if (articleID == -1) {
            return Files.readString(Path.of("forum/introduction/server/menu.json"), StandardCharsets.UTF_8);
        } else {
            String returnFile = Files.readString(Path.of("forum/introduction/server/" + articleID + ".html"));
            if (returnFile != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/server/" + articleID + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }

    @PostMapping("/qo/apihook")
    public String webhook(@RequestBody String data) {
        System.out.println(data);
        return null;

    }

@RequestMapping("/introduction/attractions")
    public String attractionIntros(@RequestParam(name = "articleID", required = true) int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/attractions/menu.json"), StandardCharsets.UTF_8);
        } else {
            String returnFile = Files.readString(Path.of("forum/introduction/attractions/" + articleID + ".html"));
            if (returnFile != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/attractions/" + articleID + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/introduction/commands")
    public String commandIntros(@RequestParam(name = "articleID", required = true)int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/commands/menu.json"), StandardCharsets.UTF_8);
        } else {
            String returnFile = Files.readString(Path.of("forum/introduction/commands/" + articleID + ".html"));
            if (returnFile != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/commands/" + articleID + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/introduction/notice")
    public String notice(@RequestParam(name = "articleID", required = true)int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/notices/menu.json"), StandardCharsets.UTF_8);
        } else {
            String returnFile = Files.readString(Path.of("forum/introduction/notices/" + articleID + ".html"));
            if (returnFile != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/notices/" + articleID + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
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

    @PostMapping("/qo/survival/msgupload")
    public String survivalUpload(@RequestBody String data, HttpServletRequest request){
        if (IPUtil.getIpAddr(request).equals("127.0.0.1")) {
            StringBuilder sb = new StringBuilder();
            sb.append("[SURVIVAL]").append(data);
            SurvivalMsgList.put(sb.toString(), 0);
        }
        return null;
    }
    @PostMapping("/qo/sponsor")
    public String getSponsor(@RequestBody String data){
        JSONObject SponsorObj = new JSONObject(data);
        System.out.println(data);
        JSONObject returnObj = new JSONObject();
        returnObj.put("ec", 200);
        return returnObj.toString();
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
                //Closed
            }
            default -> serverAlive = -1;
            //Unexpected status
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
    @PostMapping("/qo/creative/msgupload")
    public String creativeUpload(@RequestBody String data){
        Logger.log("[CreativeCHAT]" + data, Logger.LogLevel.INFO);
        CreativeMsgList.put(data, 0);
        return null;
    }
    @PostMapping("/qo/upload/gametimerecord")
    public void parser(@RequestParam(name = "name", required = true) String name,@RequestParam(name = "time", required = true) int time){
        UserProcess.handleTime(name,time);
    }
    @GetMapping("/qo/download/getgametime")
    public String getTime(String username) {
        String result = UserProcess.getTime(username).toString();
        return result;
    }
    @GetMapping("/qo/survival/msgdownload")
    public String survivalDownload(){
        if(CreativeMsgList != null) {
            for (Map.Entry<String, Integer> entry : CreativeMsgList.entrySet()) {
                String latestMessage = null;
                if (entry.getValue() == 0) {
                    latestMessage = entry.getKey();
                    // Set the associated integer value to 1
                    entry.setValue(1);
                    break; // Exit the loop after finding the latest message
                }
            }
        } else {
            return ReturnInterface.failed("INVALID");
        }
        return null;
    }
    @GetMapping("/qo/creative/msgdownload")
    public String creativeDownload(){
        if (SurvivalMsgList != null) {
            for (Map.Entry<String, Integer> entry : SurvivalMsgList.entrySet()) {
                String latestMessage = null;
                if (entry.getValue() == 0) {
                    latestMessage = entry.getKey();
                    // Set the associated integer value to 1
                    entry.setValue(1);
                    return ReturnInterface.success(latestMessage);
                }
            }
        } else {
            return ReturnInterface.success("INVALID");
        }
        return null;
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
        returnObj.put("version", 5);
        returnObj.put("die", false);
        return returnObj.toString();
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
    @PostMapping("/qo/upload/CrStatus")
    public String handleCrUpload(@RequestBody String data, HttpServletRequest request){
        if (data != null) {
           CreativeStatus = data;
        }
        return null;
    }
    @RequestMapping("/forum/register")
    public String register(@NotNull String username, @NotNull String password, @NotNull String token, HttpServletRequest request) throws Exception{
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
        return ReturnInterface.failed("FAILED");
    }
    @GetMapping("/qo/download/status")
    public String returnStatus() {
        serverStatus = "[]";
        serverStatus = status;
        return serverStatus;
    }
    @GetMapping("/qo/download/CrStatus")
    public String rgetStatus() {
        serverStatus = "[]";
        serverStatus = CreativeStatus;
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
    public static String InsertData(@RequestParam @NotNull String name,@RequestParam @NotNull Long uid, HttpServletRequest request) throws Exception {
        return UserProcess.regMinecraftUser(name, uid, request);
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
    @RequestMapping("/api/getNotice")
    public static String notice(){
        try (BufferedReader br = new BufferedReader(new FileReader(noticeData))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line);
                return content.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject returnObj = new JSONObject();
        returnObj.put("title", "无法获取更新");
        returnObj.put("info", "ERROR");
        return returnObj.toString();
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
    public void handleMsg(@RequestBody String data){
        Msg.put(data);
    }
    @GetMapping("/qo/msglist/download")
    public String returnMsg(){
        return Msg.get().toString();
    }
}
