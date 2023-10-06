package org.qo;

import org.json.JSONObject;
public class ReturnInterface {
    public static String failed(String input){
        JSONObject returnObject = new JSONObject();
        returnObject.put("code", -1);
        returnObject.put("message", input);
        return returnObject.toString();
    }
    public static String success(String input){
        JSONObject returnObject = new JSONObject();
        returnObject.put("code", 0);
        returnObject.put("message", input);
        return returnObject.toString();
    }
}
