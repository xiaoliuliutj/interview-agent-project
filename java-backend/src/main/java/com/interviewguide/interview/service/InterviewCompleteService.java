package com.interviewguide.interview.service;

import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that completes one interview session. */
@Service
public class InterviewCompleteService {
    /** Provides the complete session-completion workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before completing a session. */
    private final UserIdentityResolver identityResolver;

    /** Creates the completion endpoint service. */
    public InterviewCompleteService(InterviewWorkflowService workflowService,
                                    UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Completes the caller-owned session through the Python agent. */
    public void complete(String sessionId, String userId) {
        // Resolve the owner before changing persistent session state.
        String ownerId = identityResolver.require(userId);
        // Delegate the complete lower-agent completion workflow.
        workflowService.complete(sessionId, ownerId);
    }
}
