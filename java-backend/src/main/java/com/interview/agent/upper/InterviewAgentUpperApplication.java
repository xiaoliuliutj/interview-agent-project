package com.interview.agent.upper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class InterviewAgentUpperApplication {
    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentUpperApplication.class, args);
    }
}
