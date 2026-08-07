package com.interview.agent.upper.agent;

public class AgentGatewayException extends RuntimeException {
    private final boolean retryable;

    public AgentGatewayException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
