package com.interview.agent.upper.api.dto;

public record LegacySubmitAnswerResponse(
        boolean hasNextQuestion,
        LegacyInterviewQuestion nextQuestion,
        int currentIndex,
        int totalQuestions) {
}
