package org.qo.server;

import org.qo.Request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AvatarCache {
    public static final String CachePath = "avatars/";
    public static void init() throws IOException {
        if (!Files.exists(Path.of(CachePath))){
            Files.createDirectory(Path.of(CachePath));
        }
    }
    public static boolean has(String name){
        return Files.exists(Path.of(CachePath + name + ".png"));
    }
    public static void cache(String url, String name) throws Exception{
        Request.Download(url,CachePath + name);
    }
}
