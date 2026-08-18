package com.interviewguide.common.web;

/** Structured lower-service error metadata included in an API failure response. */
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
