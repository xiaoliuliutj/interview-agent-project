package com.interviewguide.interview.service;

import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that finds an unfinished interview for a resume. */
@Service
public class InterviewUnfinishedService {
    /** Provides the complete unfinished-session query workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before querying sessions. */
    private final UserIdentityResolver identityResolver;

    /** Creates the unfinished-session endpoint service. */
    public InterviewUnfinishedService(InterviewWorkflowService workflowService,
                                      UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Returns the caller's latest unfinished session for the specified resume. */
    public InterviewResponse find(String resumeId, String userId) {
        // Resolve the owner before querying user-scoped records.
        String ownerId = identityResolver.require(userId);
        // Delegate the unfinished-session lookup.
        return workflowService.findUnfinished(ownerId, resumeId);
    }
}
