package org.qo;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.qo.datas.ConnectionPool;
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

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;


import static org.qo.utils.Logger.LogLevel.*;
@EnableScheduling
@SpringBootApplication
@EnableAsync
public class Main {
    @Resource
    private FileUpdateHook fileUpdateHook;

    @Resource
    private Redis redis;

    @PreDestroy
    public void onShutdown() {
        Logger.log("Stopping API...", INFO);
        fileUpdateHook.stop();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
        }
        ConnectionPool.init(); //EVERY thing using SQL should init after this!
        Msg.Companion.init();
        Configuration.INSTANCE.init();
        Funcs.Start();
        Init.INSTANCE.init();
        Funcs.ShowDic();
        Logger.log("API Started.", INFO);
        SpringApplication.run(ApiApplication.class, args);
        Logger.startLogWriter("log.log", 3000);
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(Boolean.FALSE);
        config.addAllowedOrigin("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
