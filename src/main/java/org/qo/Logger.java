package org.qo;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Logger {
    public static void Log(String message, int lvl) {
        try {
            FileWriter fw = new FileWriter("log.log", true);
            Date now = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String timestamp = sdf.format(now);
            switch (lvl) {
                case 0:
                    fw.write("[" + timestamp + "] [INFO] " + message + "\n");
                    break;
                case 1:
                    fw.write("[" + timestamp + "] [WARNING] " + message + "\n");
                    break;
                case 2:
                    fw.write("[" + timestamp + "] [ERROR] " + message + "\n");
                    break;
                default:
                    fw.write("[" + timestamp + "] [UNKNOWN] " + message + "\n");
            }

            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
