package com.interviewguide.interview.dto;

import java.time.Instant;
import java.util.Map;

public record InterviewView(
        String sessionId,
        String userId,
        String candidateId,
        String resumeId,
        String jdId,
        String interviewDirection,
        String difficulty,
        int totalQuestions,
        String status,
        long stateVersion,
        String currentQuestion,
        String currentStage,
        int issuedQuestionCount,
        int primaryQuestionCount,
        int totalPrimaryQuestionCount,
        int followupCount,
        Map<String, Object> finalEvaluation,
        Instant createdAt,
        Instant updatedAt) {
}
