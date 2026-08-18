package com.interviewguide.common.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PythonAgentHttpConfiguration {
    @Bean
    public RestClient agentRestClient(
            RestClient.Builder builder,
            @Value("${agent.python.base-url}") String baseUrl,
            @Value("${agent.python.connect-timeout:2s}") Duration connectTimeout,
            @Value("${agent.python.read-timeout:11m}") Duration readTimeout) {
        // Uvicorn serves the lower Agent over HTTP/1.1. Explicitly preventing
        // Java's HTTP client from attempting an h2c upgrade keeps the JSON body
        // intact; otherwise Uvicorn rejects the upgrade request before FastAPI
        // can validate the standard Agent contract.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(readTimeout);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
