package com.medication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {RedisAutoConfiguration.class})
@EnableAsync
@EnableScheduling
public class MedicationGuideApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicationGuideApplication.class, args);
    }
}