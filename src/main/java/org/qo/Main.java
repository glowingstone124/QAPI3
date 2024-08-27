package org.qo;
import org.qo.mcsmanager.InstanceUtil;
import org.qo.orm.ORMKt;
import org.qo.redis.Configuration;
import org.qo.server.SystemInfo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;


import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.qo.Logger.LogLevel.*;
@EnableScheduling
@SpringBootApplication
public class Main {
    public static void main(String[] args) throws Exception {
        SystemInfo si = new SystemInfo();
        si.printSystemInfo();
        if (args.length != 0) {
            for (String arg : args) {
                if (arg.equals("--disable-redis")) {
                    Configuration.EnableRedis = false;
                }
            }
        }
        ConnectionPool.init();

        //Mail mail = new Mail();
        //if (!mail.test()){
            //Logger.log("Mail function doesn't work properly. With following exception:", ERROR);
           // Logger.log("", ERROR);
        //}

        org.qo.redis.Configuration.init();
        Funcs.Start();
        Funcs.ShowDic();
        InstanceUtil iu = new InstanceUtil();
        iu.run();
        Logger.log("API Started.", INFO);
        SpringApplication.run(ApiApplication.class, args);
        Logger.startLogWriter("log.log", 3000);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.log("API shutdown.", INFO);
        }));
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
    @Bean
    public FilterRegistrationBean<Filter> filter() {
        FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new Filter());
        registrationBean.addUrlPatterns("/*");
        return registrationBean;
    }
}
