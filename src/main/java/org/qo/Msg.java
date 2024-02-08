package org.qo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Msg {
    public static List<String> msgList = new ArrayList<>();

    public static void put(String msg) {
        try (FileWriter fw = new FileWriter("chathistory.txt", StandardCharsets.UTF_8, true)) {
            fw.write(msg + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (msgList.size() >= 300) {
            msgList.remove(0);
        }
        msgList.add(msg);
    }
    public static JsonObject get() {
        JsonObject jsonObject = new JsonObject();
        JsonArray jsonArray = new JsonArray();
        for (String msg : msgList) {
            jsonArray.add(msg);
        }
        jsonObject.add("messages", jsonArray);
        jsonObject.addProperty("empty", jsonArray.isEmpty());
        return jsonObject;
    }

}
