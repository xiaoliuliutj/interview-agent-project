package com.interview.agent.upper.config;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AgentHttpConfiguration {
    @Bean
    public RestClient agentRestClient(
            RestClient.Builder builder,
            @Value("${agent.python.base-url}") String baseUrl) {
        // Uvicorn serves the lower Agent over HTTP/1.1. Explicitly preventing
        // Java's HTTP client from attempting an h2c upgrade keeps the JSON body
        // intact; otherwise Uvicorn rejects the upgrade request before FastAPI
        // can validate the standard Agent contract.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return builder
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(client))
                .build();
    }
}
