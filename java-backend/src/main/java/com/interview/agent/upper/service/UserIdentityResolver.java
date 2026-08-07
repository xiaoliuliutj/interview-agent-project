package com.interview.agent.upper.service;

import org.springframework.stereotype.Component;

/**
 * Temporary identity boundary used until the application is connected to its
 * authentication provider. The header is an identity key, not an authority
 * claim; authorization is still checked against the owning domain record.
 */
@Component
public class UserIdentityResolver {
    public String require(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("USER_ID_REQUIRED", "X-User-Id header is required");
        }
        return userId.strip();
    }
}
