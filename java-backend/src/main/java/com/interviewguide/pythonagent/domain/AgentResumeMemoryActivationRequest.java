package com.interviewguide.pythonagent.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Activates the current resume version before its asynchronous evaluation starts. */
public record AgentResumeMemoryActivationRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String subjectId,
        @NotBlank String candidateId,
        @NotBlank String inputText,
        @NotBlank String targetRole,
        @NotNull Instant timestamp) {
}
