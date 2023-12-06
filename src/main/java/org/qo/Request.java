package org.qo;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class Request {

    public static String sendPostRequest(String targetUrl, String data) throws Exception {
        String result = "";
        HttpURLConnection connection = null;
        try {
            // Create a connection to the target URL.
            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();

            // Set the request method to POST and the content type to application/json.
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            // Enable output and input for the connection.
            connection.setDoOutput(true);
            connection.setDoInput(true);

            // Send the request data.
            try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
                outputStream.writeBytes(data);
            }

            // Read the response from the server.
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result += line;
                    }
                }
            }
        } finally {
            // Close the connection to the server.
            if (connection != null) {
                connection.disconnect();
            }
        }
        //Bukkit.getLogger().info("request "+targetUrl);
        return result;
    }

    public static String sendGetRequest(String targetUrl) throws Exception {
        String result = "";
        HttpURLConnection connection = null;
        try {
            // Create a connection to the target URL.
            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();

            // Set the request method to POST and the content type to application/json.
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");

            // Enable output and input for the connection.
            connection.setDoOutput(true);
            connection.setDoInput(true);

            // Read the response from the server.
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result += line;
                    }
                }
            }
        } finally {
            // Close the connection to the server.
            if (connection != null) {
                connection.disconnect();
            }
        }
        //Bukkit.getLogger().info("request "+targetUrl);
        return result;
    }
}
