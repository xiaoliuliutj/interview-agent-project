package com.interview.agent.upper.api.dto;

public record LegacyCurrentQuestion(
        boolean completed,
        LegacyInterviewQuestion question,
        String message) {
}
