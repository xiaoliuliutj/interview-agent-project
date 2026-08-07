package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.LegacyCreateInterviewRequest;
import com.interview.agent.upper.api.dto.LegacyCurrentQuestion;
import com.interview.agent.upper.api.dto.LegacyInterviewSession;
import com.interview.agent.upper.api.dto.LegacyInterviewListItem;
import com.interview.agent.upper.api.dto.LegacySubmitAnswerRequest;
import com.interview.agent.upper.api.dto.LegacySubmitAnswerResponse;
import com.interview.agent.upper.service.LegacyInterviewFacade;
import com.interview.agent.upper.service.InterviewReportPdfService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/** 保留原 React 面试 API 的协议适配器。 */
@RestController
@RequestMapping("/api/interview/sessions")
public class LegacyInterviewController {
    private final LegacyInterviewFacade facade;
    private final InterviewReportPdfService reportPdfService;

    public LegacyInterviewController(LegacyInterviewFacade facade, InterviewReportPdfService reportPdfService) {
        this.facade = facade;
        this.reportPdfService = reportPdfService;
    }

    @GetMapping
    public ApiResult<List<LegacyInterviewListItem>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.list(userId));
    }

    @PostMapping
    public ApiResult<LegacyInterviewSession> create(
            @RequestBody LegacyCreateInterviewRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.create(request, userId));
    }

    @GetMapping("/{sessionId}")
    public ApiResult<LegacyInterviewSession> get(@PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.get(sessionId, userId));
    }

    @GetMapping("/{sessionId}/question")
    public ApiResult<LegacyCurrentQuestion> question(@PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.currentQuestion(sessionId, userId));
    }

    @GetMapping("/unfinished/{resumeId}")
    public ApiResult<LegacyInterviewSession> unfinished(
            @PathVariable String resumeId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.unfinished(resumeId, userId));
    }

    @PostMapping("/{sessionId}/answers")
    public ApiResult<LegacySubmitAnswerResponse> answer(
            @PathVariable String sessionId,
            @RequestBody LegacySubmitAnswerRequest request,
            @RequestHeader(value = "X-Run-Id", required = false) String runId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.submit(sessionId, userId, request.answer(), runId));
    }

    @PutMapping("/{sessionId}/answers")
    public ApiResult<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody LegacySubmitAnswerRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        facade.saveDraft(sessionId, request.answer(), userId);
        return ApiResult.success(null);
    }

    @GetMapping("/{sessionId}/report")
    public ApiResult<Map<String, Object>> report(@PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(facade.report(sessionId, userId));
    }

    @GetMapping("/{sessionId}/details")
    public ApiResult<Map<String, Object>> details(@PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        LegacyInterviewSession session = facade.get(sessionId, userId);
        return ApiResult.success(Map.of(
                "sessionId", session.sessionId(),
                "totalQuestions", session.totalQuestions(),
                "status", session.status(),
                "answers", facade.turns(sessionId)));
    }

    @GetMapping("/{sessionId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        LegacyInterviewSession session = facade.get(sessionId, userId);
        Map<String, Object> report = facade.report(sessionId, userId);
        byte[] pdf = reportPdfService.render(session.sessionId(), session.status(), session.totalQuestions(), report);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"interview-" + sessionId + ".pdf\"")
                .body(pdf);
    }

    @DeleteMapping("/{sessionId}")
    public ApiResult<Void> delete(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        facade.delete(sessionId, userId);
        return ApiResult.success(null);
    }

    @PostMapping("/{sessionId}/complete")
    public ApiResult<Void> complete(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        facade.complete(sessionId, userId);
        return ApiResult.success(null);
    }
}
