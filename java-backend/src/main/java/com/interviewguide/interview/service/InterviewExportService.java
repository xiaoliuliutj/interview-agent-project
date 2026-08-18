package com.interviewguide.interview.service;

import com.interviewguide.interview.domain.InterviewDetailResponse;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that exports one interview report as a PDF file. */
@Service
public class InterviewExportService {
    /** Provides the complete interview-detail workflow. */
    private final InterviewWorkflowService workflowService;
    /** Resolves the request owner before exporting a report. */
    private final UserIdentityResolver identityResolver;
    /** Renders generic PDF text pages for the export response. */
    private final InterviewReportRenderService reportPdfUtil;

    /** Creates the export endpoint service. */
    public InterviewExportService(InterviewWorkflowService workflowService, UserIdentityResolver identityResolver,
                                  InterviewReportRenderService reportPdfUtil) {
        // Save the complete business workflow.
        this.workflowService = workflowService;
        // Save the common request-identity resolver.
        this.identityResolver = identityResolver;
        // Save the PDF rendering dependency.
        this.reportPdfUtil = reportPdfUtil;
    }

    /** Builds a PDF export for the caller-owned interview session. */
    public ResponseEntity<byte[]> export(String sessionId, String userId) {
        // Resolve the owner before reading exportable session data.
        String ownerId = identityResolver.require(userId);
        // Load the authoritative session and turn data once.
        InterviewDetailResponse detail = workflowService.detail(sessionId, ownerId);
        // Render the interview-specific data into a downloadable PDF byte sequence.
        byte[] content = reportPdfUtil.render(sessionId, detail.session().status(),
                detail.session().totalQuestions(), detail.turns(), detail.session().finalEvaluation());
        // Return the complete binary response contract for this endpoint.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"interview-" + sessionId + ".pdf\"")
                .body(content);
    }
}
