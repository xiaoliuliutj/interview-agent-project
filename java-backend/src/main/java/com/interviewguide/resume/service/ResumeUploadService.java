package com.interviewguide.resume.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** Handles the single HTTP use case that uploads and starts analysis for one resume. */
@Service
public class ResumeUploadService {
    /** Provides the complete resume upload workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the upload endpoint service. */
    public ResumeUploadService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Stores the uploaded resume and submits its analysis task. */
    public Map<String, Object> upload(MultipartFile file, String targetRole, String userId) throws IOException {
        // Delegate validation, storage, persistence and task creation.
        return workflowService.upload(file, targetRole, userId);
    }
}
