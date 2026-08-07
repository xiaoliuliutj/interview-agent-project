package com.interview.agent.upper.api.dto;

import java.time.Instant;

public record ScheduleView(
        Long id,
        String userId,
        String title,
        Instant startAt,
        Instant endAt,
        String source,
        String status,
        String rawText) {
}
