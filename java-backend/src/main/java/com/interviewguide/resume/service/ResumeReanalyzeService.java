package com.interviewguide.resume.service;

import com.interviewguide.resume.domain.ResumeAnalysisResponse;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that submits fresh analysis for a resume. */
@Service
public class ResumeReanalyzeService {
    /** Provides the complete analysis-task submission workflow. */
    private final ResumeWorkflowService workflowService;

    /** Creates the reanalysis endpoint service. */
    public ResumeReanalyzeService(ResumeWorkflowService workflowService) {
        // Save the complete reusable resume workflow.
        this.workflowService = workflowService;
    }

    /** Submits a new analysis task for the caller-owned current resume. */
    public ResumeAnalysisResponse reanalyze(String resumeId, String targetRole, String userId) {
        // Delegate ownership validation and analysis-task creation.
        return workflowService.reanalyze(resumeId, targetRole, userId);
    }
}
