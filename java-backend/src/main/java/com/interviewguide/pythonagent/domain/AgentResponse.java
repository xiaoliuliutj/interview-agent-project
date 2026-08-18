package com.interviewguide.pythonagent.domain;

import java.util.Map;

/** Common response envelope received from the Python service by every Java adapter call. */
public record AgentResponse(
        String apiVersion,
        String requestId,
        String runId,
        int code,
        String status,
        String userId,
        String sessionId,
        String sessionStatus,
        long stateVersion,
        String answer,
        String turnStage,
        String currentStage,
        Map<String, Object> output,
        AgentError error,
        String timestamp) {

    /** Returns whether the lower service explicitly marked a server-side failure as retryable. */
    public boolean retryable() {
        // Retry only structured 5xx failures; client and contract errors must be acknowledged.
        return error != null && error.retryable() && code >= 500 && code < 600;
    }
}
