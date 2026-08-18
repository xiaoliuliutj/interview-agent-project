package com.interviewguide.pythonagent.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Java-to-Python contract for creating and initializing an interview agent session. */
public record AgentInitializeRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @Valid @NotNull CandidateSnapshot candidate,
        @NotNull Instant timestamp) {

    public record CandidateSnapshot(
            @NotBlank String candidateId,
            @NotBlank String resumeId,
            String jdId,
            @NotBlank String resumeText,
            String jdText,
            @NotBlank String targetRole,
            @NotNull @Min(15) @Max(120) Integer interviewDurationMinutes,
            @NotBlank String desiredDifficulty,
            String interviewDirection,
            @NotNull List<Map<String, Object>> customCategories,
            @NotNull List<String> systemKnowledgeBaseIds,
            @NotNull List<String> userKnowledgeBaseIds) {
    }
}
