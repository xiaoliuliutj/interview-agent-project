package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record ScheduleRequest(
        @NotBlank String userId,
        @NotBlank String title,
        Instant startAt,
        Instant endAt,
        String source,
        String rawText) {
}
