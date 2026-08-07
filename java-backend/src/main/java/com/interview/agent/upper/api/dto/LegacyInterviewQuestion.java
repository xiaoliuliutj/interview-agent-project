package com.interview.agent.upper.api.dto;

public record LegacyInterviewQuestion(
        int questionIndex,
        String question,
        String type,
        String category,
        String userAnswer,
        Integer score,
        String feedback) {
}
