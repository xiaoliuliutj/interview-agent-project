package com.interviewguide.interview.service;

import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

import java.util.List;

/** Handles the single HTTP use case that lists a user's interview sessions. */
@Service
public class InterviewListService {
    /** Provides the complete interview query workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before listing sessions. */
    private final UserIdentityResolver identityResolver;

    /** Creates the list endpoint service. */
    public InterviewListService(InterviewWorkflowService workflowService, UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Lists sessions belonging to the resolved caller. */
    public List<InterviewResponse> list(String userId) {
        // Resolve the owner before querying user-scoped records.
        String ownerId = identityResolver.require(userId);
        // Delegate the complete session-list projection.
        return workflowService.list(ownerId);
    }
}
