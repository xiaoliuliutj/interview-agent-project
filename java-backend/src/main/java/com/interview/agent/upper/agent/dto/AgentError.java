package com.interview.agent.upper.agent.dto;

public record AgentError(String type, String message, boolean retryable) {
}
