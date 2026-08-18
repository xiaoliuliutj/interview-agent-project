package com.interviewguide.common.python;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.pythonagent.domain.AgentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Validates the common response envelope returned by the separate Python
 * service before Java applies an interview result.
 */
@Component
public class PythonAgentResponseValidator {
    /**
     * Requires a successful envelope whose identity matches the original Java
     * request, preventing delayed results from being applied to another run.
     */
    public void requireMatchingResponse(AgentResponse response, String errorCode,
                                        String userId, String sessionId, String runId) {
        // Status validation must happen before any value from the response is trusted.
        requireSuccess(response, errorCode);
        // A returned result must always belong to the exact initiating request.
        if (!userId.equals(response.userId()) || !sessionId.equals(response.sessionId()) || !runId.equals(response.runId())) {
            // Reject cross-request data instead of allowing it to corrupt the session.
            throw new BusinessException("AGENT_RESPONSE_IDENTITY_MISMATCH",
                    "lower Agent response does not match the submitted session or run");
        }
    }

    /** Converts an unsuccessful Python envelope into the Java API error contract. */
    private void requireSuccess(AgentResponse response, String errorCode) {
        // Python reserves 100-199 for successful Agent responses.
        if (response == null || response.code() < 100 || response.code() >= 200) {
            // Prefer the lower service's structured message when it was supplied.
            String message = response != null && response.error() != null
                    ? response.error().message() : "lower agent processing failed";
            // Preserve a specific lower-layer error type when one is available.
            String type = response != null && response.error() != null && response.error().type() != null
                    ? response.error().type() : errorCode;
            // Only the lower service can designate a gateway failure as retryable.
            boolean retryable = response != null && response.error() != null && response.error().retryable();
            // Select the most useful operation stage for API diagnostics.
            String stage = response == null ? "AGENT_CALL"
                    : firstNonBlank(response.turnStage(), response.currentStage(), response.status());
            // Translate the response into the common Java failure envelope.
            throw new BusinessException(type, message, retryable,
                    retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                    response == null ? null : response.requestId(),
                    response == null ? null : response.runId(),
                    response == null ? null : response.sessionId(), stage);
        }
    }

    /** Returns the first non-blank protocol stage supplied by Python. */
    private String firstNonBlank(String... values) {
        // Preserve the first semantic value instead of returning an empty label.
        for (String value : values) {
            // A value must contain visible content to be useful to clients.
            if (value != null && !value.isBlank()) return value;
        }
        // No usable stage was supplied by the lower service.
        return null;
    }
}
