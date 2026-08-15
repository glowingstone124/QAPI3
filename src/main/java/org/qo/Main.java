package org.qo;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.qo.redis.Redis;
import org.qo.services.mmdb.Init;
import org.qo.redis.Configuration;
import org.qo.services.messageServices.Msg;
import org.qo.utils.FileUpdateHook;
import org.qo.utils.Funcs;
import org.qo.utils.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import java.util.Arrays;


import static org.qo.utils.Logger.LogLevel.*;
@EnableScheduling
@SpringBootApplication
@EnableAsync
public class Main {
    @Resource
    private FileUpdateHook fileUpdateHook;

    @PreDestroy
    public void onShutdown() {
        Logger.log("Stopping API...", INFO);
        fileUpdateHook.stop();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
        }
        org.qo.redis.Configuration.INSTANCE.init();
        Funcs.Start();
        Init.INSTANCE.init();
        Funcs.ShowDic();
        Logger.log("API Started.", INFO);
        SpringApplication.run(ApiApplication.class, args);
        Logger.startLogWriter("log.log", 3000);
    }

    @Bean
    public CorsWebFilter corsFilter(@Value("${qapi.cors.allowed-origins:*}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(Boolean.FALSE);
        Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .forEach(config::addAllowedOrigin);
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Token"));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "DELETE", "OPTIONS"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!config.getAllowedOrigins().isEmpty()) source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
