package com.deductionaudit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeductionAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeductionAuditApplication.class, args);
    }
}
