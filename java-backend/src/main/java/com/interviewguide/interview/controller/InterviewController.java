package com.interviewguide.interview.controller;

import com.interviewguide.common.web.ApiResult;
import com.interviewguide.interview.domain.InterviewDetailResponse;
import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.interview.service.InterviewAgentStatusService;
import com.interviewguide.interview.service.InterviewAnswerSubmitService;
import com.interviewguide.interview.service.InterviewCompleteService;
import com.interviewguide.interview.service.InterviewDeleteService;
import com.interviewguide.interview.service.InterviewDetailService;
import com.interviewguide.interview.service.InterviewExportService;
import com.interviewguide.interview.service.InterviewListService;
import com.interviewguide.interview.service.InterviewPauseService;
import com.interviewguide.interview.service.InterviewStartService;
import com.interviewguide.interview.service.InterviewUnfinishedService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** The only public API for text interviews. */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    /** Request body for creating a text interview session. */
    public record StartInterviewRequest(
            @NotBlank String resumeId,
            @NotBlank String targetRole,
            @Min(15) @Max(120) int interviewDurationMinutes,
            @NotBlank String desiredDifficulty,
            String interviewDirection,
            String jdText,
            @NotNull List<Map<String, Object>> customCategories) {
    }

    /** Request body for submitting one interview answer. */
    public record SubmitInterviewAnswerRequest(@NotBlank String answer) {
    }

    /** Handles session creation. */
    private final InterviewStartService startService;
    /** Handles caller-visible session listing. */
    private final InterviewListService listService;
    /** Handles the session-and-turn detail query. */
    private final InterviewDetailService detailService;
    /** Handles lower Agent status retrieval. */
    private final InterviewAgentStatusService agentStatusService;
    /** Handles one resume's unfinished-session lookup. */
    private final InterviewUnfinishedService unfinishedService;
    /** Handles one submitted interview answer. */
    private final InterviewAnswerSubmitService answerSubmitService;
    /** Handles explicit session completion. */
    private final InterviewCompleteService completeService;
    /** Handles explicit session pausing. */
    private final InterviewPauseService pauseService;
    /** Handles PDF report generation. */
    private final InterviewExportService exportService;
    /** Handles caller-owned session deletion. */
    private final InterviewDeleteService deleteService;

    /** Injects exactly one endpoint service for each controller route. */
    public InterviewController(InterviewStartService startService, InterviewListService listService,
                               InterviewDetailService detailService, InterviewAgentStatusService agentStatusService,
                               InterviewUnfinishedService unfinishedService,
                               InterviewAnswerSubmitService answerSubmitService,
                               InterviewCompleteService completeService, InterviewPauseService pauseService,
                               InterviewExportService exportService, InterviewDeleteService deleteService) {
        this.startService = startService;
        this.listService = listService;
        this.detailService = detailService;
        this.agentStatusService = agentStatusService;
        this.unfinishedService = unfinishedService;
        this.answerSubmitService = answerSubmitService;
        this.completeService = completeService;
        this.pauseService = pauseService;
        this.exportService = exportService;
        this.deleteService = deleteService;
    }

    @PostMapping
    /** Creates a session and requests its first question from the lower Agent. */
    public ApiResult<InterviewResponse> start(@Valid @RequestBody StartInterviewRequest request,
                                          @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(startService.start(userId, request.resumeId(),
                request.targetRole(), request.interviewDurationMinutes(), request.desiredDifficulty(),
                request.interviewDirection(), request.jdText(), request.customCategories()));
    }

    @GetMapping
    /** Lists the authenticated caller's interview sessions. */
    public ApiResult<List<InterviewResponse>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(listService.list(userId));
    }

    @GetMapping("/{sessionId}")
    /** Returns one caller-owned session together with its saved turns. */
    public ApiResult<InterviewDetailResponse> get(@PathVariable String sessionId,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(detailService.detail(sessionId, userId));
    }

    @GetMapping("/{sessionId}/agent-status")
    /** Reads the current lower-Agent status for one caller-owned session. */
    public ApiResult<java.util.Map<String, Object>> agentStatus(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(agentStatusService.status(sessionId, userId));
    }

    @GetMapping("/unfinished/{resumeId}")
    /** Returns the current unfinished session for one resume when it exists. */
    public ApiResult<InterviewResponse> unfinished(@PathVariable String resumeId,
                                                 @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(unfinishedService.find(resumeId, userId));
    }

    @PostMapping("/{sessionId}/answers")
    /** Saves one answer and obtains the resulting next interview state. */
    public ApiResult<InterviewDetailResponse> submitAnswer(@PathVariable String sessionId,
                                                   @Valid @RequestBody SubmitInterviewAnswerRequest request,
                                                   @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(answerSubmitService.submit(sessionId, request.answer(), userId));
    }

    @PostMapping("/{sessionId}/complete")
    /** Marks one session completed after ownership validation. */
    public ApiResult<Void> complete(@PathVariable String sessionId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {
        completeService.complete(sessionId, userId);
        return ApiResult.success(null);
    }

    @PostMapping("/{sessionId}/pause")
    /** Pauses one caller-owned session without generating another question. */
    public ApiResult<Void> pause(@PathVariable String sessionId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId) {
        pauseService.pause(sessionId, userId);
        return ApiResult.success(null);
    }

    @GetMapping("/{sessionId}/export")
    /** Returns the generated report bytes for one caller-owned session. */
    public ResponseEntity<byte[]> export(@PathVariable String sessionId,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return exportService.export(sessionId, userId);
    }

    @DeleteMapping("/{sessionId}")
    /** Deletes one caller-owned session and all its turns. */
    public ApiResult<Void> delete(@PathVariable String sessionId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId) {
        deleteService.delete(sessionId, userId);
        return ApiResult.success(null);
    }

}
