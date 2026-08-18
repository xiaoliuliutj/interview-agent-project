package com.interviewguide.resume.service;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Handles the single HTTP use case that downloads an original resume file. */
@Service
public class ResumeDownloadService {
    /** Provides the complete original-file download workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the download endpoint service. */
    public ResumeDownloadService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Returns the caller-owned original file response. */
    public ResponseEntity<byte[]> download(String resumeId, String userId) throws IOException {
        // Delegate authorization and stored-file retrieval.
        return workflowService.download(resumeId, userId);
    }
}
