package com.interviewguide.common.python;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.pythonagent.domain.AgentResponse;

import java.util.Map;

/** Validates and unwraps the generic output envelope of Python web-tool calls. */
public final class PythonAgentWebResponseValidator {
    /** Prevents construction because this validator stores no state. */
    private PythonAgentWebResponseValidator() {
    }

    /** Returns the protocol output map or raises a stable Java business error. */
    public static Map<String, Object> requireOutput(AgentResponse response, String code, String fallback) {
        // HTTP transport success is insufficient when the protocol code or output body is invalid.
        if (response == null || response.code() < 100 || response.code() >= 200 || response.output() == null) {
            // Prefer the structured error supplied by Python for actionable client feedback.
            String message = response != null && response.error() != null
                    ? response.error().message() : fallback;
            // Expose the stable Java error code rather than a lower-service implementation detail.
            throw new BusinessException(code, message);
        }
        // Return only output that has satisfied the common lower-service contract.
        return response.output();
    }
}
