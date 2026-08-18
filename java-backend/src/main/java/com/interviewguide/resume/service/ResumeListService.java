package com.interviewguide.resume.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Handles the single HTTP use case that lists a user's resumes. */
@Service
public class ResumeListService {
    /** Provides the complete resume-list workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the list endpoint service. */
    public ResumeListService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Lists all resumes visible to the caller. */
    public List<Map<String, Object>> list(String userId) {
        // Delegate ownership-aware list construction.
        return workflowService.list(userId);
    }
}
