package com.interview.agent.upper.api.dto;

import java.time.Instant;

public record InterviewView(
        String sessionId,
        String userId,
        String candidateId,
        String resumeId,
        String jdId,
        String skillId,
        String difficulty,
        int totalQuestions,
        String status,
        long stateVersion,
        String currentQuestion,
        String currentStage,
        Instant createdAt,
        Instant updatedAt) {
}
