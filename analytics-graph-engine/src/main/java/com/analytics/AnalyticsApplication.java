package com.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Главный класс приложения Analytics Graph Engine
 */
@SpringBootApplication
@EnableScheduling
public class AnalyticsApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
