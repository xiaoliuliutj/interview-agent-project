package com.interviewguide.common.exception;

import org.springframework.http.HttpStatus;

/** Stable business failure carrying API-visible code, retryability and request metadata. */
public class BusinessException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final HttpStatus httpStatus;
    private final String requestId;
    private final String runId;
    private final String sessionId;
    private final String stage;

    /** Creates a normal non-retryable conflict-style business failure. */
    public BusinessException(String code, String message) {
        this(code, message, false, HttpStatus.CONFLICT, null, null, null, null);
    }

    /** Creates a fully described business failure for the central API exception handler. */
    public BusinessException(String code, String message, boolean retryable, HttpStatus httpStatus,
                             String requestId, String runId, String sessionId, String stage) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.runId = runId;
        this.sessionId = sessionId;
        this.stage = stage;
    }

    /** Returns the stable public business error code. */
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public HttpStatus httpStatus() { return httpStatus; }
    public String requestId() { return requestId; }
    public String runId() { return runId; }
    public String sessionId() { return sessionId; }
    public String stage() { return stage; }
}
