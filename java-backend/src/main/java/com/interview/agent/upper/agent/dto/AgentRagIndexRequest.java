package com.interview.agent.upper.agent.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentRagIndexRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String documentContent,
        @NotNull @Size(min = 1, max = 1) List<@NotBlank String> knowledgeBaseIds,
        @NotBlank String documentId,
        @NotBlank String sourceName,
        @NotNull Instant timestamp) {
}
