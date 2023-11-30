package org.qo;

import com.google.gson.JsonObject;

public class ReturnInterface {
    public static String failed(String input) {
        JsonObject returnObject = new JsonObject();
        returnObject.addProperty("code", -1);
        returnObject.addProperty("message", input);
        return returnObject.toString();
    }

    public static String success(String input) {
        JsonObject returnObject = new JsonObject();
        returnObject.addProperty("code", 0);
        returnObject.addProperty("message", input);
        return returnObject.toString();
    }

    public static String denied(String input) {
        JsonObject returnObject = new JsonObject();
        returnObject.addProperty("code", 1);
        returnObject.addProperty("message", input);
        return returnObject.toString();
    }
}
