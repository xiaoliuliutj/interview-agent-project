package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitInterviewAnswerRequest(
        @NotBlank String answer,
        @NotBlank String runId) {
}
