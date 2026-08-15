package com.interviewguide.interview.controller;

import jakarta.validation.constraints.NotBlank;

public record SubmitInterviewAnswerRequest(
        @NotBlank String answer,
        @NotBlank String runId) {
}
