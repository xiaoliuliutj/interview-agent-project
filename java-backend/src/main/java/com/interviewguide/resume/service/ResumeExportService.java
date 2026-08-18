package com.interviewguide.resume.service;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that exports one resume report as PDF. */
@Service
public class ResumeExportService {
    /** Provides the complete resume PDF export workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the export endpoint service. */
    public ResumeExportService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Returns the caller-owned resume PDF response. */
    public ResponseEntity<byte[]> export(String resumeId, String userId) {
        // Delegate authorization, report assembly and PDF rendering.
        return workflowService.export(resumeId, userId);
    }
}
