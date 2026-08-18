package com.interviewguide.interview.service;

import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Handles the single HTTP use case that starts an interview session. */
@Service
public class InterviewStartService {
    /** Provides the complete interview workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before starting a session. */
    private final UserIdentityResolver identityResolver;

    /** Creates the start endpoint service. */
    public InterviewStartService(InterviewWorkflowService workflowService, UserIdentityResolver identityResolver) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
    }

    /** Starts an owned interview session and initializes the Python agent. */
    public InterviewResponse start(String userId, String resumeId, String targetRole, int durationMinutes,
                                   String difficulty, String direction, String jdText,
                                   List<Map<String, Object>> customCategories) {
        // Resolve the owner before creating any persistent session state.
        String ownerId = identityResolver.require(userId);
        // Execute the complete session-creation and Python-initialization workflow.
        return workflowService.start(ownerId, resumeId, targetRole, durationMinutes, difficulty,
                direction, jdText, customCategories);
    }
}
