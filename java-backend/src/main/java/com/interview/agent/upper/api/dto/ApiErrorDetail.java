package com.interview.agent.upper.api.dto;

public record ApiErrorDetail(
        String type,
        String message,
        boolean retryable,
        int httpStatus,
        String requestId,
        String runId,
        String sessionId,
        String stage) {
}
