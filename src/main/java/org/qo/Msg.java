package org.qo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Msg {
    private static final int MAX_QUEUE_SIZE = 300;
    private static BlockingQueue<String> msgQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    public static String webGet() {
        JsonObject returnObj = new JsonObject();
        if (msgQueue.isEmpty()) {
            returnObj.addProperty("code", 400);
        } else {
            returnObj.addProperty("code", 0);
            returnObj.addProperty("content", msgQueue.peek());
        }
        return returnObj.toString();
    }

    public static void put(String msg) {

        try (FileWriter fw = new FileWriter("chathistory.txt", StandardCharsets.UTF_8, true)) {
            fw.write(msg + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (msgQueue.size() >= MAX_QUEUE_SIZE) {
            msgQueue.poll();
        }
        msgQueue.offer(msg);
    }

    public static JsonObject get() {
        JsonObject jsonObject = new JsonObject();
        JsonArray jsonArray = new JsonArray();
        for (String msg : msgQueue) {
            jsonArray.add(msg);
        }
        jsonObject.add("messages", jsonArray);
        jsonObject.addProperty("empty", jsonArray.isEmpty());
        return jsonObject;
    }
}
