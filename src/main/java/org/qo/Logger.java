package org.qo;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class Logger {
    private static List<String> logBuffer = new ArrayList<>();
    private static final Object lock = new Object();

    public static void Log(String message, int level) {
        Date now = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = sdf.format(now);
        String logEntry;

        switch (level) {
            case 0 -> logEntry = "[" + timestamp + "] [INFO] " + message;
            case 1 -> logEntry = "[" + timestamp + "] [WARNING] " + message;
            case 2 -> logEntry = "[" + timestamp + "] [ERROR] " + message;
            default -> logEntry = "[" + timestamp + "] [UNKNOWN] " + message;
        }

        synchronized (lock) {
            logBuffer.add(logEntry);
        }
    }

    public static void startLogWriter(String logFilePath, long intervalMillis) {
        Timer timer = new Timer(true);
        timer.schedule(new LogWriterTask(logFilePath), intervalMillis, intervalMillis);
    }

    private static class LogWriterTask extends TimerTask {
        private final String logFilePath;

        LogWriterTask(String logFilePath) {
            this.logFilePath = logFilePath;
        }

        @Override
        public void run() {
            List<String> logsToWrite;

            synchronized (lock) {
                logsToWrite = new ArrayList<>(logBuffer);
                logBuffer.clear();
            }

            if (!logsToWrite.isEmpty()) {
                int logWriteCnt = 0;
                try (FileWriter fw = new FileWriter(logFilePath, true)) {
                    for (String logEntry : logsToWrite) {
                        fw.write(logEntry + "\n");
                        logWriteCnt++;
                    }
                    System.out.println("This row updated " + logWriteCnt + " line(s) log.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                //System.out.println("No logs to write.");
            }
        }
    }
}
