package com.interviewguide.resume.service;

import org.springframework.stereotype.Service;

import java.io.IOException;

/** Handles the single HTTP use case that deletes one resume and dependent records. */
@Service
public class ResumeDeleteService {
    /** Provides the complete resume-deletion workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the delete endpoint service. */
    public ResumeDeleteService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Deletes the caller-owned resume, analysis tasks, sessions and stored file. */
    public void delete(String resumeId, String userId) throws IOException {
        // Delegate the complete dependent-resource deletion workflow.
        workflowService.delete(resumeId, userId);
    }
}
