package com.interviewguide.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

/** Spring Boot entry point that scans all approved business and common modules. */
@SpringBootApplication(scanBasePackages = "com.interviewguide")
@MapperScan("com.interviewguide")
public class InterviewAgentUpperApplication {
    /** Starts the Java application through Spring Boot. */
    public static void main(String[] args) {
        // Delegate application-context creation and lifecycle handling to Spring.
        SpringApplication.run(InterviewAgentUpperApplication.class, args);
    }
}
