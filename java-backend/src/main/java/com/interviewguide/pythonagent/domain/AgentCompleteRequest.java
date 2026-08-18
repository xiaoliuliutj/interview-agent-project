package com.interviewguide.pythonagent.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 鍏抽棴涓嬪眰 Agent 浼氳瘽鐨勫唴閮ㄨ姹傦紝涓嶆壙杞藉€欓€変汉鍥炵瓟銆?*/
public record AgentCompleteRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String sessionStatus,
        @Min(0) long stateVersion,
        @NotNull Instant timestamp) {
}
