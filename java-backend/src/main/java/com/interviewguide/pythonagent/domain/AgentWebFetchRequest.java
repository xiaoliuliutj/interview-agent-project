package com.interviewguide.pythonagent.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request passed to the lower Agent web-reading tool. */
public record AgentWebFetchRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank @Size(max = 2048) String url,
        Instant timestamp) {
}
