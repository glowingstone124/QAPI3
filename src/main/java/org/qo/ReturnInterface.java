package org.qo;

import com.google.gson.JsonObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class ReturnInterface {
    /*
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
     */
    public static ResponseEntity<String> failed(String input){
        JsonObject returnObject = new JsonObject();
        returnObject.addProperty("code", -1);
        returnObject.addProperty("message", input);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(returnObject.toString(), headers, HttpStatus.NOT_ACCEPTABLE);
    }
    public static ResponseEntity<String>  success(String input) {
        JsonObject returnObject = new JsonObject();
        returnObject.addProperty("code", 0);
        returnObject.addProperty("message", input);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(returnObject.toString(), headers, HttpStatus.OK);
    }

    public static ResponseEntity<String>  denied(String input) {
        JsonObject returnObject = new JsonObject();
        returnObject.addProperty("code", 1);
        returnObject.addProperty("message", input);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(returnObject.toString(), headers, HttpStatus.FORBIDDEN);
    }
}