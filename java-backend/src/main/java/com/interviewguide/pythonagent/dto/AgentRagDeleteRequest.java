package com.interviewguide.pythonagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Deletes all vector chunks for one upper-layer knowledge base. */
public record AgentRagDeleteRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String knowledgeBaseId,
        @NotNull Instant timestamp) {
}
