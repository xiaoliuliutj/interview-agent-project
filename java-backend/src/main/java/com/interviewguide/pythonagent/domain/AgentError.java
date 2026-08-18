package com.interviewguide.pythonagent.domain;

/** Structured error supplied by Python: stable type, message and retryability hint. */
public record AgentError(String type, String message, boolean retryable) {
}
