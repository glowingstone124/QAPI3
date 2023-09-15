package org.qo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mysql.cj.log.Log;
import com.mysql.cj.protocol.a.authentication.Sha256PasswordPlugin;
import com.sun.source.tree.ReturnTree;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.websocket.OnError;
import org.apache.catalina.User;
import org.jetbrains.annotations.NotNull;
import org.json.HTTP;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.lang.management.OperatingSystemMXBean;


@RestController
@SpringBootApplication
public class ApiApplication {
    public static String status111;
    public static String serverStatus;
    public static String survivalMsg;
    public static String creativeMsg;
    public static String CreativeStatus;
    public static String jdbcUrl = "jdbc:mysql://localhost:3306/qouser";
    public static String sqlusername = "root";
    public static String sqlpassword = "05b028772401827c";

    private static final List<UserInfo> status = new ArrayList<>();
    String jsonData = "data/playermap.json";
    public static String noticeData = "data/notice.json";
    public ApiApplication() throws IOException {
    }
    @RequestMapping("/")
    public String root(){
        return "QAPI PROJECT ROOT DIRECTIONARY, this is NOT an api for JCSUF or anything else.";
    }
    @RequestMapping("/introduction")
    public String introductionMenu(){
        try {
            if ((Files.readString(Path.of("forum/introduction/main.json"), StandardCharsets.UTF_8)!= null)){
                return Files.readString(Path.of("forum/introduction/main.json"));
            }
        } catch (IOException e){
            return ReturnInterface.failed("ERROR:CONFIGURATION NOT FOUND");
        }
        return ReturnInterface.failed("ERROR");
    }
    @RequestMapping("/introduction/server")
    public String serverIntros(@NotNull int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/server/menu.json"), StandardCharsets.UTF_8);
        } else {
            if (UserProcess.queryArticles(articleID, 0) != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/server/" + UserProcess.queryArticles(articleID, 0) + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/introduction/attractions")
    public String attractionIntros(@NotNull int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/attractions/menu.json"), StandardCharsets.UTF_8);
        } else {
            if (UserProcess.queryArticles(articleID, 2) != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/attractions/" + UserProcess.queryArticles(articleID, 2) + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/introduction/commands")
    public String commandIntros(@NotNull int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/commands/menu.json"), StandardCharsets.UTF_8);
        } else {
            if (UserProcess.queryArticles(articleID, 1) != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/commands/" + UserProcess.queryArticles(articleID, 1) + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/introduction/notice")
    public String notice(@NotNull int articleID) throws Exception{
        if (articleID == -1){
            return Files.readString(Path.of("forum/introduction/notices/menu.json"), StandardCharsets.UTF_8);
        } else {
            if (UserProcess.queryArticles(articleID, 3) != null) {
                return ReturnInterface.success(Files.readString(Path.of("forum/introduction/notices/" + UserProcess.queryArticles(articleID, 3) + ".html")));
            }
            return ReturnInterface.failed("NOT FOUND");
        }
    }
    @RequestMapping("/api/notice")
    public String JCSUF1(HttpServletRequest request) throws IOException {
        String noticedata = "data/notice.json";
        return Files.readString(Path.of(noticedata));
    }
    @RequestMapping("/qo/app")
    public String returnContent() throws IOException {
        String index = Files.readString(Path.of("webs/index.html"));
        return index;
    }
    @GetMapping("/forum/login")
    public String userLogin(String username, String password , HttpServletRequest request) {
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 准备查询语句
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setString(1, username);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String storedHashedPassword = resultSet.getString("password");
                String encryptPswd = hashSHA256(password);
                if (Objects.equals(encryptPswd, storedHashedPassword)) {
                    Logger.Log(IPUtil.getIpAddr(request) + username + " login successful.", 0);
                    return ReturnInterface.success("成功");
                }
            } else {
                Logger.Log("username " + username + " login failed.", 0);
                return ReturnInterface.failed("登录失败");
            }
            connection.close();
            preparedStatement.close();
        } catch (SQLException e) {
            e.printStackTrace();
            JSONObject responseJson = new JSONObject();
            responseJson.put("code", -1);
            return responseJson.toString();
        }
        return ReturnInterface.failed("NULL");
    }
    @JsonProperty("myinfo")
    @RequestMapping("/forum/fetch/myself")
    public String myinfo(String name, HttpServletRequest request) throws Exception {
        String date;
        Boolean premium;
        Boolean donate;
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 准备查询语句
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setString(1, name);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                // Move the cursor to the first row and retrieve data
                date = resultSet.getString("date");
                premium = resultSet.getBoolean("premium");
                donate = resultSet.getBoolean("donate");
                String linkto = resultSet.getString("linkto");
                JSONObject responseJson = new JSONObject();
                responseJson.put("date", date);
                responseJson.put("premium", premium);
                responseJson.put("donate", donate);
                responseJson.put("code", 0);
                responseJson.put("linkto", linkto);
                responseJson.put("username", name);
                Logger.Log(IPUtil.getIpAddr(request) + "username " + name + " qureied myinfo.", 0);
                return responseJson.toString();
            } else {
                // No rows found for the given username
                JSONObject responseJson = new JSONObject();
                responseJson.put("code", -1);
                Logger.Log(IPUtil.getIpAddr(request) + "username " + name + " qureied myinfo, but unsuccessful.", 0);
                return responseJson.toString();
            }

        } catch (SQLException e) {
            e.printStackTrace();
            JSONObject responseJson = new JSONObject();
            Logger.Log("username " + name + " qureied myinfo, but unsuccessful.", 0);
            responseJson.put("code", -1);
            return responseJson.toString();
        }
    }

    @PostMapping("/qo/survival/msgupload")
    public String survivalUpload(@RequestBody String data){
        survivalMsg = data;
        return null;
    }
    @RequestMapping("/api/isFirstLogin")
    public String firstLoginSearch(@RequestParam String name, HttpServletRequest request) {
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername,sqlpassword);

            // 准备查询语句
            String query = "SELECT * FROM forum WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setString(1, name);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();

            // 处理查询结果
            if (resultSet.next()) {
                Boolean first = resultSet.getBoolean("firstLogin");
                JSONObject responseJson = new JSONObject();
                responseJson.put("code", 0);
                responseJson.put("first", first);
                // 关闭资源
                resultSet.close();
                preparedStatement.close();

                return responseJson.toString();
            }
            String updateQuery = "UPDATE forum SET firstLogin = ? WHERE username = ?";
            PreparedStatement apreparedStatement = connection.prepareStatement(updateQuery);
            // 设置更新参数值
            apreparedStatement.setBoolean(1, false);
            apreparedStatement.setString(2, name);

            // 执行更新操作
            Logger.Log(IPUtil.getIpAddr(request) +" username " + name + " qureied firstlogin.", 0);
            int rowsAffected = apreparedStatement.executeUpdate();
            apreparedStatement.close();
            connection.close();
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

// 处理错误的情况
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("first", -1);
        Logger.Log(IPUtil.getIpAddr(request) + " username " + name + " qureied firstlogin but unsuccessful.", 0);
        return responseJson.toString();
    }
    @PostMapping("/qo/creative/msgupload")
    public String creativeUpload(@RequestBody String data){
        Logger.Log("[CreativeCHAT]" + data, 0);
        creativeMsg = data;
        return null;
    }
    @GetMapping("/qo/survival/msgdownload")
    public String survivalDownload(){
        if(creativeMsg != null) {
            return creativeMsg;
        } else {
            return "INVALID";
        }
    }
    @GetMapping("/qo/creative/msgdownload")
    public String creativeDownload(){
        if (survivalMsg != null) {
            return survivalMsg;
        } else {
            return "INVALID";
        }
    }
    @RequestMapping("/app/latest")
    public String update(){
        JSONObject returnObj = new JSONObject();
        returnObj.put("version", 3);
        returnObj.put("die", false);
        return returnObj.toString();
    }
    @RequestMapping("/qo/download/systeminfo")
    public String systeminfo(){
        JSONObject systemInfoJson = new JSONObject();

        // Get CPU usage
        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        String cpuUsage = String.valueOf(operatingSystemMXBean.getSystemLoadAverage());
        systemInfoJson.put("cpu_usage", cpuUsage);

        // Get memory usage
        JSONObject memoryUsageJson = getMemoryUsage();
        systemInfoJson.put("memory_usage", memoryUsageJson);

        // Get disk usage
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

    // Get disk usage
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
    public long timedate() {
        long timeStamp = System.currentTimeMillis();
        return timeStamp;
    }
    @PostMapping("/qo/upload/status")
    public String handlePost(@RequestBody String data, HttpServletRequest request) {
        if (data != null && IPUtil.getIpAddr(request).equals("127.0.0.1")) {
            status111 = data;
        }
        return null;
    }
    @PostMapping("/qo/upload/CrStatus")
    public String handleCrUpload(@RequestBody String data){
        if (data != null) {
           CreativeStatus = data;
        }
        return null;
    }
    @RequestMapping("/forum/register")
    public String register(@NotNull String username, @NotNull String password, @NotNull String token, HttpServletRequest request) throws Exception{
        if(Objects.equals(token, token(username,1700435)) && !UserProcess.queryForum(username)){
            try {
                regforum(username, password);
            } catch (NullPointerException e){
                e.printStackTrace();
            } catch (Exception ex){

                ex.printStackTrace();
            }
            Logger.Log(IPUtil.getIpAddr(request) + "[register]" + username + " registered.", 0);
            return ReturnInterface.success("SUCCESS");
        }
        Logger.Log(IPUtil.getIpAddr(request) + "[register]" + username + " registered but failed.", 0);
        return ReturnInterface.failed("FAILED");
    }
    @GetMapping("/qo/download/status")
    public String returnStatus() {
        serverStatus = "[]";
        serverStatus = status111;
        return serverStatus;
    }
    @GetMapping("/qo/download/CrStatus")
    public String rgetStatus() {
        serverStatus = "[]";
        serverStatus = CreativeStatus;
        return serverStatus;
    }
    @PostMapping("/qo/upload/rebind")
    public String handleRebind(String player_name, String uid, String token) throws IOException {
        String unusedToken = token(player_name, Long.parseLong(uid));
        if(Objects.equals(token,unusedToken)) {
            reBind(player_name, uid);
            Logger.Log("[rebind]" + player_name + " rebinded.", 0);
            return "Success!";
        }
        Logger.Log("[rebind]" + player_name + " rebinded but failed.", 0);
        return "FAILED";
    }
    private static String token(String player_name, long qq) {
        String charset = "qazxswedcvfrtgbnhyujmkiolp0129384756_POILKJMNBUYTHGFVCXREWQDSAZ";
        ArrayList<Integer> remix = new ArrayList<>();
        if(qq<=0) throw new IllegalArgumentException("Invalid QQ ID");
        long qq_copy = qq;
        for (qq = qq + 707; qq!=0; qq/=64) {
            remix.add((int) (qq%64));
        }
        for (char c:player_name.toCharArray()) {
            if(charset.indexOf(c)==-1) throw new IllegalArgumentException("Invalid player name character '"+c+"' in "+player_name);
            remix.add(charset.indexOf(c));
        }
        if(remix.size()%2==1) remix.add((int) (qq_copy%32) * 2);
        String result = "";
        double node = 707;
        int size = remix.size()/2;
        for(int i = 0; i < 16; i++) {
            double value = 0;
            for(int j = 0; j < size; j++) {
                value += Math.sin(remix.get(j * 2) * node + remix.get(j * 2 + 1));
            }
            node += value * 707;
            result = result + Integer.toHexString(sigmoid(value));
        }
        return result;
    }

    private static int sigmoid(double value) {
        double sigmoid_result = 1d/(1d+Math.exp(0-value));
        int result = (int) Math.floor(sigmoid_result * 256);
        if(result >= 256) return 255;
        else return Math.max(result, 0);
    }
    private void regforum(String username, String password) throws Exception{
        // 解析JSON数据为JSONArray
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1; // 注意月份是从0开始计数的
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        String date = year + "-" + month + "-" + day;
        String EncryptedPswd = hashSHA256(password);
        Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);
        // 准备插入语句
        String insertQuery = "INSERT INTO forum (username, date, password, premium, donate, firstLogin, linkto) VALUES (?, ?, ?, ?, ?, ?, ?)";
        Logger.Log(insertQuery, 0);
        PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);
        // 设置参数值
        preparedStatement.setString(1, username);
        preparedStatement.setString(2, date);
        preparedStatement.setString(3, EncryptedPswd);
        preparedStatement.setBoolean(4, false);
        preparedStatement.setBoolean(5, false);
        preparedStatement.setBoolean(6, true);
        preparedStatement.setString(7, "EMPTY");
        preparedStatement.executeUpdate();
        // 关闭资源
        preparedStatement.close();
        connection.close();
    }
    public static String hashSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte hashByte : hashBytes) {
                String hex = Integer.toHexString(0xff & hashByte);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
    public boolean reBind(String name, String uid) throws IOException {
        JSONArray jsonArray;
        jsonArray = new JSONArray(jsonData);
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject user = jsonArray.optJSONObject(i);
            if (user.optString("username", "").equals(name)) {
                // 用户已存在，更新uid和remain
                user.put("uid", uid);
                int remain = user.optInt("remain", 3);
                user.put("remain", Math.max(0, remain - 1));
                Files.write(Paths.get(jsonData), jsonArray.toString(4).getBytes());
                return true;
            }
        }
        return false;
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
    public static String InsertData(@NotNull String name, @NotNull Long uid, HttpServletRequest request) throws Exception {
        if (!UserProcess.dumplicateUID(uid) && !name.equals(null) && !uid.equals(null)) {
            if (!UserProcess.hasIp(IPUtil.getIpAddr(request))) {
                try {
                    // 连接到数据库
                    Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

                    // 准备插入语句
                    String insertQuery = "INSERT INTO users (username, uid,frozen, remain) VALUES (?, ?, ?, ?)";
                    PreparedStatement preparedStatement = connection.prepareStatement(insertQuery);

                    // 设置参数值
                    preparedStatement.setString(1, name);
                    preparedStatement.setLong(2, uid);
                    preparedStatement.setBoolean(3, false);
                    preparedStatement.setInt(4, 3);
                    // 执行插入操作
                    int rowsAffected = preparedStatement.executeUpdate();
                    System.out.println(rowsAffected + " row(s) inserted." + "from " + IPUtil.getIpAddr(request));
                    System.out.println(preparedStatement.toString());
                    // 关闭资源
                    preparedStatement.close();
                    connection.close();
                    UserProcess.insertIp(IPUtil.getIpAddr(request));
                    return "Success!";
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return "FAILED";
            } else {
                return  ReturnInterface.failed("Used IP");
            }
        } else if(name.equals(null) || uid.equals(null)){
            Logger.Log("Register ERROR: username or uid null", 2);
        }
        return "FAILED";
    }
        @RequestMapping("/qo/download/memorial")
        public String downloadMemorial() throws IOException {
            String filePath = "data/memorial.json";

            // Read the content of the file and return it as the response
            try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    content.append(line);
                }
                return content.toString();
            } catch (IOException e) {
                e.printStackTrace();
                throw e;
            }
        }
    @RequestMapping("/qo/download/avatar")
    public String avartarTrans(@RequestParam String name) throws Exception {
        String apiURL = "https://api.mojang.com/users/profiles/minecraft/" + name;
        String avartarURL = "https://crafatar.com/avatars/";
        URL url = new URL(apiURL);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        int responseCode = con.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            JSONObject playerUUIDobj = new JSONObject(response.toString());
            String useruuid = playerUUIDobj.getString("id");
            String username = playerUUIDobj.getString("name");
            JSONObject returnObject = new JSONObject();
            returnObject.put("url", avartarURL + useruuid);
            returnObject.put("name", name);
            return returnObject.toString();
        } else {
            JSONObject returnObject = new JSONObject();
            returnObject.put("url", avartarURL + "8667ba71b85a4004af54457a9734eed7");
            returnObject.put("name", name);
            return returnObject.toString();
        }
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
    public static String GetData(String name) throws Exception{
        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 准备查询语句
            String query = "SELECT * FROM users WHERE username = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            // 设置查询参数
            preparedStatement.setString(1, name);

            // 执行查询
            ResultSet resultSet = preparedStatement.executeQuery();

            // 处理查询结果
            if (resultSet.next()) {
                Long uid = resultSet.getLong("uid");
                Boolean frozen = resultSet.getBoolean("frozen");
                    JSONObject responseJson = new JSONObject();
                    responseJson.put("code", 0);
                    responseJson.put("frozen", frozen);
                    responseJson.put("qq", uid);
                    return responseJson.toString();
                }
            // 关闭资源
            resultSet.close();
            preparedStatement.close();
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        JSONObject responseJson = new JSONObject();
        responseJson.put("code", 1);
        responseJson.put("qq", -1);
        return responseJson.toString();
    }
    public static void updateHash() {

        try {
            // 连接到数据库
            Connection connection = DriverManager.getConnection(jdbcUrl, sqlusername, sqlpassword);

            // 查询所有记录
            String query = "SELECT * FROM forum";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            ResultSet resultSet = preparedStatement.executeQuery();

            // 准备更新密码的语句
            String updateQuery = "UPDATE forum SET password = ? WHERE username = ?";
            PreparedStatement updateStatement = connection.prepareStatement(updateQuery);

            while (resultSet.next()) {
                String username = resultSet.getString("username");
                String plainPassword = resultSet.getString("password");

                // 将密码进行SHA-256哈希处理
                String hashedPassword = hashSHA256(plainPassword);

                // 更新数据库中的密码
                updateStatement.setString(1, hashedPassword);
                updateStatement.setString(2, username);
                updateStatement.executeUpdate();
            }

            System.out.println("Passwords hashed and updated successfully.");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
