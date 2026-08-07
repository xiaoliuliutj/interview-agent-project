package com.interview.agent.upper.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentInitializeRequest(
        String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @Valid @NotNull CandidateSnapshot candidate) {

    public record CandidateSnapshot(
            @NotBlank String candidateId,
            @NotBlank String resumeId,
            String jdId,
            String resumeText,
            String jdText,
            @NotBlank String targetRole,
            Integer interviewDurationMinutes,
            String desiredDifficulty) {
    }
}
