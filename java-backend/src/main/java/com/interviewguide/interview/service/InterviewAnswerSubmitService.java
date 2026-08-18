package com.interviewguide.interview.service;

import com.interviewguide.interview.domain.InterviewDetailResponse;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that submits one interview answer. */
@Service
public class InterviewAnswerSubmitService {
    /** Provides the complete answer-submission workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before changing a session. */
    private final UserIdentityResolver identityResolver;

    /** Creates the answer-submit endpoint service. */
    public InterviewAnswerSubmitService(InterviewWorkflowService workflowService,
                                        UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Submits an answer to Python and returns the resulting persisted session detail. */
    public InterviewDetailResponse submit(String sessionId, String answer, String userId) {
        // Resolve the owner before changing persistent session state.
        String ownerId = identityResolver.require(userId);
        // Execute the full answer submission workflow.
        workflowService.submitAnswer(sessionId, ownerId, answer);
        // Read the authoritative persisted state produced by that workflow.
        return workflowService.detail(sessionId, ownerId);
    }
}
