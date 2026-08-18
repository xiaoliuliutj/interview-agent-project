package com.interviewguide.common.exception;

/** Transport failure while invoking the separate Python service. */
public class PythonAgentException extends RuntimeException {
    private final boolean retryable;

    public PythonAgentException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
