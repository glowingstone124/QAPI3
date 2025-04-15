package org.qo.utils;

import org.jetbrains.annotations.Nullable;
import org.qo.services.mail.Mail;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class Logger {
    private static Mail mail = new Mail();

    private static List<String> logBuffer = new ArrayList<>();
    private static List<String> logMsgs = new ArrayList<>();
    private static final Object lock = new Object();
    public static void log(String message, @Nullable Object level) {
        String logEntry = null;
        if (level == null || level instanceof Integer) {
            System.out.println("your code are using a non-specified log level (int, boolean, etc.) please correct this.");
        }
        if (level instanceof LogLevel) {
            Date now = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String timestamp = sdf.format(now);
            logEntry = String.format("[%s] [%s] %s", timestamp,  level != null ? level : "UNSPECIFIED LOG LEVEL", message);
        }
        if (logEntry != null) {
            synchronized (lock) {
                logBuffer.add(logEntry);
                logMsgs.add(logEntry);
            }
        }
    }


    public static void startLogWriter(String logFilePath, long intervalMillis) {
        Timer timer = new Timer(true);
        timer.schedule(new LogWriterTask(logFilePath), intervalMillis, intervalMillis);
        //timer.schedule(new MailTask(),0, 3600000);
    }

    public enum LogLevel {
        INFO, WARNING, ERROR, UNKNOWN
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
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true))) {
                    for (String logEntry : logsToWrite) {
                        writer.write(logEntry + "\n");
                        System.out.println(logEntry + "\n");
                        logWriteCnt++;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private static class MailTask extends TimerTask {

        @Override
        public void run() {
            StringBuilder result = new StringBuilder();
            logMsgs.forEach(item -> {
                result.append(item).append("\n");
            });
            mail.send("hanserofficial@outlook.com", "LOG report", result.toString());
            logMsgs.clear();
        }
    }
}
