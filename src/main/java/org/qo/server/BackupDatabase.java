package org.qo.server;

import org.qo.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimerTask;

public class BackupDatabase extends TimerTask {
    public static String dbName  = "qouser";
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
    String formattedDate = dateFormat.format(new Date());
    String path = "database/backup_" + formattedDate + ".sql";
    @Override
    public void run() {
        if (!Files.exists(Path.of("database"))){
            try {
                Files.createDirectory(Path.of("database"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        try {
            Process process = Runtime.getRuntime().exec("mysqldump " + dbName + " > " + path);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Logger.log("Database backup complete.", Logger.LogLevel.INFO);
                upload(path);
            } else {
                Logger.log("ERROR IN backup DATABASE!", Logger.LogLevel.ERROR);
                upload(path);
            }
        } catch (IOException | InterruptedException e) {
            Logger.log("Exception caused!", Logger.LogLevel.ERROR);
            Logger.log(e.getMessage(), Logger.LogLevel.ERROR);
        }
    }

    private void upload(String filePath) {
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI("http://mc17.rhymc.com:52000/qo/upload/backup")) // 替换为实际的API端点
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(Path.of(filePath)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Logger.log("Database upload complete.", Logger.LogLevel.INFO);
            } else {
                Logger.log("Database upload ERROR with response code " + response.statusCode(), Logger.LogLevel.ERROR);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
