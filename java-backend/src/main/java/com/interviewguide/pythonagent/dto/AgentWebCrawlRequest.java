package com.interviewguide.pythonagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record AgentWebCrawlRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank @Size(max = 2048) String url,
        @Size(max = 500) String topic,
        Instant timestamp) {
}
