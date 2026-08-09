package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Frontend text-interview request. The upper layer resolves the authenticated user. */
public record StartInterviewRequest(
        @NotBlank String resumeId,
        @NotBlank String targetRole,
        @Min(15) @Max(120) int interviewDurationMinutes,
        @Min(2) @Max(30) int questionCount,
        @NotBlank String desiredDifficulty,
        String skillId,
        String jdText,
        @NotNull List<Map<String, Object>> customCategories) {
}
