package org.qo;
import org.qo.datas.ConnectionPool;
import org.qo.services.mmdb.Init;
import org.qo.redis.Configuration;
import org.qo.services.messageServices.Msg;
import org.qo.utils.Funcs;
import org.qo.utils.Logger;
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
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
        }
        ConnectionPool.init(); //EVERY thing using SQL should init after this!
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Logger.log("API shutdown.", INFO)));
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
