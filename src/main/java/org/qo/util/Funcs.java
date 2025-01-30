package org.qo.util;


import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.qo.repository.Database;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
@Service
public class Funcs {
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    @Deprecated
    public static void ShowDic(){
        System.out.println("Api Dictionary: \n");
    }
    public static void Start() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.log("API shutdown.", Logger.LogLevel.ERROR);
        }));
        System.out.println("""
                QApi Opensource Project
                启动中...
                Based On Springboot                             
                """);
        if (!Database.SQLAvliable()) {
            System.out.println("SQL Misconfigured!");
        }
    }
    public boolean verify(String input, Perms perms) throws Exception {
        Gson gson = new Gson();
        String jsonContent = Files.readString(Path.of(UserProcess.CODE_CONFIGURATION));
        JsonObject codes = gson.fromJson(jsonContent, JsonObject.class);
        if (codes.has(input)){
            String pm = codes.get(input).getAsString();
            Perms map = Perms.valueOf(pm);
            if (perms.compareTo(map) >= 0){
                return true;
            }
        }
        return false;
    }


    public enum Perms{
        FULL(999),
        CHATSYNC(1);

        private final int value;

        Perms(int i) {
            this.value = i;
        }

        public int getValue() {
            return value;
        }
    }
    public static String generateRandomString(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            int index = random.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }
        return sb.toString();
    }
}
