package com.interview.agent.upper.api.dto;

import java.time.Instant;

/** 适配原 React 历史列表的最小字段集合。 */
public record LegacyInterviewListItem(
        String sessionId,
        String skillId,
        String difficulty,
        Long resumeId,
        int totalQuestions,
        String status,
        String evaluateStatus,
        String evaluateError,
        Integer overallScore,
        Instant createdAt,
        Instant completedAt) {
}
