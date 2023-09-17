package org.qo;


public class Funcs {
    public static void Start(){
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.Log("API shutdown.", 2);
        }));
        System.out.println("""
                Quantum Original Api Project
                启动中...
                Based On Springboot                             
                """);
        if (!UserProcess.SQLAvliable()) {
            System.out.println("SQL Misconfigured!");
        }
    }
    public static <A> boolean isNull(A variable) {
        return variable == null;
    }
}
