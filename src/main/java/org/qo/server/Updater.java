package org.qo.server;
import org.json.JSONObject;
import org.qo.Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class Updater {
    public static String Dirdic;
    final String latestFilePath = "latest.txt";
    static Scanner scanner = new Scanner(System.in);

    public static void executeCommand(){
        String project = "purpur";
        String minecraftVersion = "1.20.2";

        try {
            String apiUrl = "https://api.purpurmc.org/v2/" + project + "/" + minecraftVersion + "/";
            String response = sendGETRequest(apiUrl);
            String latestBuild = extractLatestBuild(response);
            System.out.println("Latest Build: " + latestBuild);

            String latestFilePath = "D:\\proj\\qo2\\latest.txt";
            int latestValue = readLatestValue(latestFilePath);

            int latestBuildValue = Integer.parseInt(latestBuild);
            if (latestBuildValue > latestValue) {
                System.out.println(latestBuildValue);
                try {
                    FileWriter writer = new FileWriter(latestFilePath);
                    writer.write(String.valueOf(latestBuildValue));
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                System.out.println("Purpur Server has an update. Automatically download latest file.");
                String osv = System.getProperty("os.name");
                if (Objects.equals(osv, "Linux")) {
                    Dirdic = "D:\\proj\\qo2";
                }
                File file = new File(Dirdic);
                file.mkdir();
                downloadFile("https://api.purpurmc.org/v2/purpur/" + minecraftVersion + "/" + latestBuildValue + "/" + "download", "/home/root1/srv/srv/srv/purpur" + "-" + minecraftVersion + "-" + latestBuild + ".jar");
                Logger.log("Downloaded Purpur Server Version 1.20.2-" + latestBuildValue, Logger.LogLevel.INFO);
                System.out.println("Purpur Server Version " + latestBuild + " download complete.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String sendGETRequest(String apiUrl) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            InputStream inputStream = connection.getInputStream();
            String response = convertStreamToString(inputStream);
            inputStream.close();
            return response;
        } else {
            throw new IOException("GET request failed with response code: " + responseCode);
        }
    }

    private static String extractLatestBuild(String response) {
        JSONObject jsonObject = new JSONObject(response);
        String latestBuild = "";
        if (jsonObject.has("builds")) {
            JSONObject buildsObject = jsonObject.getJSONObject("builds");
            if (buildsObject.has("latest")) {
                latestBuild = buildsObject.getString("latest");
            }
        }
        return latestBuild;
    }

    private static String convertStreamToString(InputStream inputStream) {
        Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }

    private static int readLatestValue(String filePath) {
        int latestValue = 0;
        try {
            File file = new File(filePath);
            Scanner scanner = new Scanner(file);
            if (scanner.hasNextInt()) {
                latestValue = scanner.nextInt();
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            System.out.println("Latest file not found.");
        }
        return latestValue;
    }
    public static void downloadFile(String fileUrl, String savePath) {
        try {
            URL url = new URL(fileUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = connection.getInputStream();
                FileOutputStream outputStream = new FileOutputStream(savePath);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                outputStream.close();
                inputStream.close();

                System.out.println("Server File downloaded successfully.");
            } else {
                System.out.println("File download failed with response code: " + responseCode);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
