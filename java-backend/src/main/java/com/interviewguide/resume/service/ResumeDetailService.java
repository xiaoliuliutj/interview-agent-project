package com.interviewguide.resume.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Handles the single HTTP use case that reads one resume and its analysis history. */
@Service
public class ResumeDetailService {
    /** Provides the complete resume-detail workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the detail endpoint service. */
    public ResumeDetailService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Returns the caller-owned resume detail model. */
    public Map<String, Object> detail(String resumeId, String userId) {
        // Delegate ownership validation and detail assembly.
        return workflowService.detail(resumeId, userId);
    }
}
