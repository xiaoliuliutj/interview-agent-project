package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitInterviewAnswerRequest(
        @NotBlank String userId,
        @NotBlank String answer,
        String runId) {
}
