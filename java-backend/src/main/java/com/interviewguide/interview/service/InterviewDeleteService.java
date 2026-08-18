package com.interviewguide.interview.service;

import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that removes one interview session. */
@Service
public class InterviewDeleteService {
    /** Provides the complete session-deletion workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before deleting a session. */
    private final UserIdentityResolver identityResolver;

    /** Creates the delete endpoint service. */
    public InterviewDeleteService(InterviewWorkflowService workflowService, UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Removes the caller-owned session and its persisted turns. */
    public void delete(String sessionId, String userId) {
        // Resolve the owner before deleting persistent session state.
        String ownerId = identityResolver.require(userId);
        // Delegate the complete session deletion workflow.
        workflowService.delete(sessionId, ownerId);
    }
}
