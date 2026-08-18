package com.interviewguide.interview.service;

import com.interviewguide.interview.domain.InterviewDetailResponse;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that reads one interview session in detail. */
@Service
public class InterviewDetailService {
    /** Provides the complete interview detail workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before reading a session. */
    private final UserIdentityResolver identityResolver;

    /** Creates the detail endpoint service. */
    public InterviewDetailService(InterviewWorkflowService workflowService, UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Returns the caller-owned session and its persisted turns. */
    public InterviewDetailResponse detail(String sessionId, String userId) {
        // Resolve the owner before exposing session data.
        String ownerId = identityResolver.require(userId);
        // Delegate the complete detail query.
        return workflowService.detail(sessionId, ownerId);
    }
}
