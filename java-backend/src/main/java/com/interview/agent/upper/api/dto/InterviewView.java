package com.interview.agent.upper.api.dto;

import java.time.Instant;

public record InterviewView(
        String sessionId,
        String userId,
        String candidateId,
        String resumeId,
        String jdId,
        int totalQuestions,
        String status,
        long stateVersion,
        String currentQuestion,
        Instant createdAt,
        Instant updatedAt) {
}
