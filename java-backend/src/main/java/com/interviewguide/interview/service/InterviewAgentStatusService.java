package com.interviewguide.interview.service;

import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Handles the single HTTP use case that reads lower-agent progress for one session. */
@Service
public class InterviewAgentStatusService {
    /** Provides the complete agent-progress workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before reading agent progress. */
    private final UserIdentityResolver identityResolver;

    /** Creates the agent-status endpoint service. */
    public InterviewAgentStatusService(InterviewWorkflowService workflowService,
                                       UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Returns Python agent progress for the caller-owned session. */
    public Map<String, Object> status(String sessionId, String userId) {
        // Resolve the owner before exposing lower-service state.
        String ownerId = identityResolver.require(userId);
        // Delegate the ownership-aware progress query.
        return workflowService.sessionProgress(sessionId, ownerId);
    }
}
