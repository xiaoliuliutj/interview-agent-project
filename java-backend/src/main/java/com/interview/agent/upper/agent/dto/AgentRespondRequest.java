package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record AgentRespondRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String sessionStatus,
        @Min(0) long stateVersion,
        @NotBlank String answer,
        @NotNull Instant timestamp) {
}
