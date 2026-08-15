package com.interviewguide.pythonagent.dto;

public record AgentError(String type, String message, boolean retryable) {
}
