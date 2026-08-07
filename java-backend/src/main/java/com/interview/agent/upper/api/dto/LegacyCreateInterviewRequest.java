package com.interview.agent.upper.api.dto;

import java.util.List;
import java.util.Map;

public record LegacyCreateInterviewRequest(
        String resumeText,
        Integer questionCount,
        Long resumeId,
        Boolean forceCreate,
        String llmProvider,
        String skillId,
        String difficulty,
        String jdText,
        List<Map<String, Object>> customCategories) {
}
