package org.qo;


public class Funcs {
    public static void ShowDic(){
        System.out.println("Api Dictionary: \n" + "SQL CONF:" + UserProcess.SQL_CONFIGURATION + "\n" + "RECOVERY CODE:" + UserProcess.CODE + "\n" + "LOG FILE: log.log\n" + "INTRODUCTION MENU: forum/introductions \n" + "MEMORIAL: data/memorial.json");
    }
    public static void Start(){
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.log("API shutdown.", Logger.LogLevel.ERROR);
        }));
        System.out.println("""
                QApi Opensource Project
                启动中...
                Based On Springboot                             
                """);
        if (!UserProcess.SQLAvliable()) {
            System.out.println("SQL Misconfigured!");
        }
    }
}
