package org.qo;

import org.apache.catalina.User;

public class Funcs {
    public static void Start(){
        System.out.println("""
                Quantum Original Api Project
                启动中...
                Based On Springboot                             
                """);
        if (!UserProcess.SQLAvliable()) {

        }
    }
}
