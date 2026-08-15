package com.interviewguide.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final HttpStatus httpStatus;
    private final String requestId;
    private final String runId;
    private final String sessionId;
    private final String stage;

    public BusinessException(String code, String message) {
        this(code, message, false, HttpStatus.CONFLICT, null, null, null, null);
    }

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

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public HttpStatus httpStatus() { return httpStatus; }
    public String requestId() { return requestId; }
    public String runId() { return runId; }
    public String sessionId() { return sessionId; }
    public String stage() { return stage; }
}
