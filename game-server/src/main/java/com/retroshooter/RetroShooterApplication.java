package com.retroshooter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RetroShooterApplication {
    public static void main(String[] args) {
        SpringApplication.run(RetroShooterApplication.class, args);
    }
}
