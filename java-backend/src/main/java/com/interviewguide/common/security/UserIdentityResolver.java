package com.interviewguide.common.security;

import com.interviewguide.common.exception.BusinessException;

import org.springframework.stereotype.Component;

/**
 * Provides the application-wide boundary for the temporary request identity
 * header until an authentication provider is integrated.
 */
@Component
public class UserIdentityResolver {
    /**
     * Validates and normalises the caller-supplied user identifier.
     *
     * @param userId value received from the X-User-Id request header
     * @return non-blank identifier with leading and trailing whitespace removed
     */
    public String require(String userId) {
        // A missing identity cannot be associated with an owning domain record.
        if (userId == null || userId.isBlank()) {
            // Keep the API error stable while authentication is not yet connected.
            throw new BusinessException("USER_ID_REQUIRED", "X-User-Id header is required");
        }
        // Use one canonical representation for all ownership comparisons.
        return userId.strip();
    }
}
