package com.interview.agent.upper.api.dto;

public record LegacyCreateInterviewRequest(
        String resumeText,
        Integer questionCount,
        Long resumeId,
        Boolean forceCreate,
        String llmProvider,
        String skillId,
        String difficulty,
        String jdText) {
}
