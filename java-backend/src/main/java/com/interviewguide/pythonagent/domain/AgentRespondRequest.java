package com.interviewguide.pythonagent.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Java-to-Python contract for submitting one interview answer to the agent. */
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
