package com.interviewguide.infrastructure.reliability;

import com.interviewguide.pythonagent.exception.PythonAgentException;
import com.interviewguide.pythonagent.dto.AgentError;
import com.interviewguide.pythonagent.dto.AgentResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCallExecutorTest {

    @Test
    void doesNotReplayARequestAfterStructuredFailedResponse() {
        AgentCallExecutor executor = new AgentCallExecutor(3, 0);
        AtomicInteger calls = new AtomicInteger();
        AgentResponse failed = failedResponse(true);

        AgentResponse actual = executor.execute(() -> {
            calls.incrementAndGet();
            return failed;
        });

        assertSame(failed, actual);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesTransportFailureWithoutAResponse() {
        AgentCallExecutor executor = new AgentCallExecutor(3, 0);
        AtomicInteger calls = new AtomicInteger();
        AgentResponse success = successResponse();

        AgentResponse actual = executor.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new PythonAgentException("temporary network failure", null, true);
            }
            return success;
        });

        assertSame(success, actual);
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryPermanentTransportFailure() {
        AgentCallExecutor executor = new AgentCallExecutor(3, 0);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(PythonAgentException.class, () -> executor.execute(() -> {
            calls.incrementAndGet();
            throw new PythonAgentException("invalid request", null, false);
        }));

        assertEquals(1, calls.get());
    }

    private static AgentResponse failedResponse(boolean retryable) {
        return new AgentResponse(
                "v1", "request", "run", 500, "FAILED", "user", "session",
                "FAILED", 0, null, null, null, Map.of(),
                new AgentError("AGENT_DEPENDENCY_ERROR", "model unavailable", retryable),
                "2026-08-12T00:00:00Z");
    }

    private static AgentResponse successResponse() {
        return new AgentResponse(
                "v1", "request", "run", 100, "COMPLETED", "user", "session",
                "ACTIVE", 1, "next question", null, "PROJECT", Map.of(), null,
                "2026-08-12T00:00:00Z");
    }
}
