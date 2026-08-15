package com.interviewguide.infrastructure.reliability;

import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.pythonagent.exception.PythonAgentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Bounded retry policy for recoverable HTTP failures to the separate Python service. */
@Component
public class AgentCallExecutor {
    private final int maxAttempts;
    private final long backoffMillis;

    public AgentCallExecutor(@Value("${agent.reliability.max-attempts:2}") int maxAttempts,
                             @Value("${agent.reliability.backoff-millis:200}") long backoffMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    public AgentResponse execute(Supplier<AgentResponse> call) {
        PythonAgentException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (PythonAgentException error) {
                last = error;
                if (!error.retryable() || attempt == maxAttempts) throw error;
                sleepBeforeRetry();
            }
        }
        throw last == null ? new PythonAgentException("Python service call failed", null, false) : last;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PythonAgentException("Python service retry was interrupted", error, false);
        }
    }
}
