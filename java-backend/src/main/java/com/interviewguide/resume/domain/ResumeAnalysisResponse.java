package com.interviewguide.resume.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public asynchronous resume-analysis result returned by resume endpoints. */
public record ResumeAnalysisResponse(
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
        List<Map<String, Object>> issues,
        String error) {
}
