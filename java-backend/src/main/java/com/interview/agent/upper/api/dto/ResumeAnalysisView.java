package com.interview.agent.upper.api.dto;

import java.time.Instant;
import java.util.List;

public record ResumeAnalysisView(
        long id,
        String status,
        Integer overallScore,
        Integer contentScore,
        Integer structureScore,
        Integer skillMatchScore,
        Integer expressionScore,
        Integer projectScore,
        String summary,
        Instant analyzedAt,
        List<String> strengths,
        List<String> suggestions,
        String error) {
}
