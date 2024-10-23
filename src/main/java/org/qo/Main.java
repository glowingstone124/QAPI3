package org.qo;
import org.qo.redis.Configuration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


import static org.qo.Logger.LogLevel.*;
@EnableScheduling
@SpringBootApplication
@EnableAsync
public class Main {
    public static void main(String[] args) throws Exception {
        //SystemInfo si = new SystemInfo();
        //si.printSystemInfo();
        if (args.length != 0) {
            for (String arg : args) {
                if (arg.equals("--disable-redis")) {
                    Configuration.INSTANCE.setEnableRedis(false);
                }
            }
        }
        ConnectionPool.init();

        //Mail mail = new Mail();
        //if (!mail.test()){
            //Logger.log("Mail function doesn't work properly. With following exception:", ERROR);
           // Logger.log("", ERROR);
        //}
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Logger.log("API shutdown.", INFO)));
        Configuration.INSTANCE.init();
        Funcs.Start();
        Funcs.ShowDic();
        Logger.log("API Started.", INFO);
        SpringApplication.run(ApiApplication.class, args);
        Logger.startLogWriter("log.log", 3000);
    }
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("http://127.0.0.1:5500")
                        .allowedMethods("GET", "POST")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
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
