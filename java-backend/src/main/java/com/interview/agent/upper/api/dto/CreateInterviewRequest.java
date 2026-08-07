package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record CreateInterviewRequest(
        @NotBlank String userId,
        @NotBlank String candidateId,
        @NotBlank String resumeId,
        String jdId,
        Integer totalQuestions,
        String desiredDifficulty,
        String skillId,
        List<Map<String, Object>> customCategories) {
    public CreateInterviewRequest(String userId, String candidateId, String resumeId,
            String jdId, Integer totalQuestions, String desiredDifficulty) {
        this(userId, candidateId, resumeId, jdId, totalQuestions, desiredDifficulty, null, List.of());
    }
}
