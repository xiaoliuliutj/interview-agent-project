package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateInterviewRequest(
        @NotBlank String userId,
        @NotBlank String candidateId,
        @NotBlank String resumeId,
        String jdId,
        Integer totalQuestions) {
}
