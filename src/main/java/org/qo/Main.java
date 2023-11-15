package org.qo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadPoolExecutor;

import static org.qo.server.Updater.executeCommand;

@SpringBootApplication
public class Main {
    public static void main(String[] args) throws Exception {
        Funcs.Start();
        Logger.Log("API Started.", 0);
        SpringApplication.run(ApiApplication.class, args);
        Logger.startLogWriter("log.log", 3000);
        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                executeCommand();
            }
        };
        long initialDelay = 0;
        long period = 12 * 60 * 60 * 1000;
        timer.scheduleAtFixedRate(task, initialDelay, period);
        Scanner scanner = new Scanner(System.in);
        String input = scanner.nextLine();
        switch (input){
            case "exit":
                System.exit(0);
            case "showdic":
                Funcs.ShowDic();
                break;
            default:
                System.out.println("NO COMMAND AVAILABLE");
        }
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("http://127.0.0.1:5500") // 允许的来源
                        .allowedMethods("GET", "POST") // 允许的HTTP方法
                        .allowedHeaders("*") // 允许的请求头信息
                        .allowCredentials(true); // 允许发送凭据（例如，cookies）
            }
        };
    }

    // 如果使用了Spring Security，还需要配置CorsFilter来处理跨域请求
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
}
