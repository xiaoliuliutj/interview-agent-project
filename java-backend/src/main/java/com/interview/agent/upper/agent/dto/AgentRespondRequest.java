package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentRespondRequest(
        String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String question) {
}
