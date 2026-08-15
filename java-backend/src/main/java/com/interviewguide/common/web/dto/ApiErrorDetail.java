package com.interviewguide.common.web.dto;

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
