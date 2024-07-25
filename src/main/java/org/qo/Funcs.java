package org.qo;


import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
@Service
public class Funcs {
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    public static void ShowDic(){
        System.out.println("Api Dictionary: \n" + "SQL CONF:" + UserProcess.SQL_CONFIGURATION + "\n" + "RECOVERY CODE:" + UserProcess.CODE + "\n" + "LOG FILE: log.log\n" + "INTRODUCTION MENU: forum/introductions \n" + "MEMORIAL: data/memorial.json");
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
            String pm = codes.get(input).getAsString(); // 修正此处，使用 input 而不是 "Perm"
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
