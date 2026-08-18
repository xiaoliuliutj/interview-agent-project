package com.interviewguide.interview.domain;

import java.time.Instant;
import java.util.Map;

/** Public interview session read model used by start, list, unfinished and detail endpoints. */
public record InterviewResponse(
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
