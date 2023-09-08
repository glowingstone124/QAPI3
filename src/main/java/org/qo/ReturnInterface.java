package org.qo;

import org.json.JSONObject;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
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
    public static String MDsolver(String md){
        String markdownText = "# Hello, Markdown!";

        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdownText);
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        return renderer.render(document);
    }
}
