package com.interviewguide.pythonagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

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

    public boolean retryable() {
        return error != null && error.retryable() && code >= 500 && code < 600;
    }
}
