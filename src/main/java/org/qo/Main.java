package org.qo;

import org.qo.mail.Mail;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;


import java.util.Timer;
import java.util.TimerTask;

import static org.qo.Logger.LogLevel.*;

@SpringBootApplication
public class Main {
    public static void main(String[] args) throws Exception {
        Mail mail = new Mail();
        if (!mail.test()){
            Logger.log("Mail function doesn't work properly. With following exception:", ERROR);
            Logger.log("", ERROR);
        }
        Funcs.Start();
        Logger.log("API Started.", INFO);
        SpringApplication.run(ApiApplication.class, args);
        Logger.startLogWriter("log.log", 3000);
        Timer timer = new Timer();
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
        config.setAllowCredentials(false);
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
        registrationBean.addUrlPatterns("/*"); // added ContentType application/json Globally
        return registrationBean;
    }
}
