package com.interviewguide.common.python;

import com.interviewguide.common.exception.PythonAgentException;
import com.interviewguide.pythonagent.domain.AgentResponse;

/**
 * Defines the shared failure, retry, and response-identity policy for
 * asynchronous Python Agent work.
 */
public final class PythonAgentFailurePolicy {
    /** Prevents construction because the policy exposes only stateless operations. */
    private PythonAgentFailurePolicy() {
    }

    /** Returns whether RabbitMQ may redeliver an exception safely. */
    public static boolean isRetryable(RuntimeException error) {
        // Only a lower-service gateway failure is transient in the current contract.
        return error instanceof PythonAgentException gatewayError && gatewayError.retryable();
    }

    /** Validates the status and request identity echoed by Python for a queued task. */
    public static void requireMatchingResponse(AgentResponse response, String fallbackMessage,
                                                String userId, String sessionId, String runId) {
        // A non-success result must not update the analysis row.
        if (response == null || response.code() < 100 || response.code() >= 200) {
            // Retain Python's structured message if the lower service supplied one.
            String message = response != null && response.error() != null
                    ? response.error().message() : fallbackMessage;
            // Preserve the retry flag so RabbitMQ's policy can make the next decision.
            throw new PythonAgentException(message, null, response != null && response.retryable());
        }
        // A late message for another request is terminal rather than retryable.
        if (!userId.equals(response.userId()) || !sessionId.equals(response.sessionId())
                || !runId.equals(response.runId())) {
            // Prevent cross-request output from corrupting persisted analysis data.
            throw new PythonAgentException(fallbackMessage + ": response identity mismatch", null, false);
        }
    }

    /** Produces a bounded message suitable for database and Redis storage. */
    public static String safeMessage(RuntimeException error) {
        // Use the exception's message as the primary diagnostic text.
        String message = error.getMessage();
        // Fall back to the exception type and bound untrusted text to 500 characters.
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }
}
