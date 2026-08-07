package com.interview.agent.upper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AgentHttpConfiguration {
    @Bean
    public RestClient agentRestClient(
            RestClient.Builder builder,
            @Value("${agent.python.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
