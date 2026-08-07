package com.interview.agent.upper.engineering.reliability;

import com.interview.agent.upper.agent.AgentGatewayException;
import com.interview.agent.upper.agent.dto.AgentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class AgentCallExecutor {
    private final int maxAttempts;
    private final long backoffMillis;

    public AgentCallExecutor(
            @Value("${agent.reliability.max-attempts:2}") int maxAttempts,
            @Value("${agent.reliability.backoff-millis:200}") long backoffMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    public AgentResponse execute(Supplier<AgentResponse> call) {
        AgentGatewayException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                AgentResponse response = call.get();
                if (!response.retryable() || attempt == maxAttempts) {
                    return response;
                }
            } catch (AgentGatewayException error) {
                lastException = error;
                if (!error.retryable() || attempt == maxAttempts) {
                    throw error;
                }
            }
            sleepBeforeRetry();
        }
        throw lastException == null
                ? new AgentGatewayException("Agent 调用失败", null, false)
                : lastException;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AgentGatewayException("Agent 重试被中断", error, false);
        }
    }
}
