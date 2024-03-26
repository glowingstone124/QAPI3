package org.qo.server;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import org.commonmark.node.Node;
import org.commonmark.node.Visitor;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
public class Documents {
    public static final String DocumentPath = "docs/";
    public static final String MenuPath = "docs/index.json";

    public String transform(String name) throws Exception{
        Parser parser = Parser.builder().build();
        Node document = parser.parse(Files.readString(Path.of(DocumentPath + name + ".md")));
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        return renderer.render(document);
    }
    public String lists() throws IOException{
        return Files.readString(Path.of(MenuPath));
    }
}
