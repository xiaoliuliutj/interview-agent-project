package com.interviewguide.interview.service;

import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that pauses one active interview session. */
@Service
public class InterviewPauseService {
    /** Provides the complete pause workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before pausing a session. */
    private final UserIdentityResolver identityResolver;

    /** Creates the pause endpoint service. */
    public InterviewPauseService(InterviewWorkflowService workflowService, UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Pauses the caller-owned active session through the Python agent. */
    public void pause(String sessionId, String userId) {
        // Resolve the owner before changing persistent session state.
        String ownerId = identityResolver.require(userId);
        // Delegate the complete lower-agent pause workflow.
        workflowService.pause(sessionId, ownerId);
    }
}
