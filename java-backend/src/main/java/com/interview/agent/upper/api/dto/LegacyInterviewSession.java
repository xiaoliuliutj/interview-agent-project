package com.interview.agent.upper.api.dto;

import java.util.List;

public record LegacyInterviewSession(
        String sessionId,
        String resumeText,
        int totalQuestions,
        int currentQuestionIndex,
        List<LegacyInterviewQuestion> questions,
        String status) {
}
