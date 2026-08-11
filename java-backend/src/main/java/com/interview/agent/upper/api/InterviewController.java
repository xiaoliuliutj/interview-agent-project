package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.InterviewDetailView;
import com.interview.agent.upper.api.dto.InterviewTurnView;
import com.interview.agent.upper.api.dto.InterviewView;
import com.interview.agent.upper.api.dto.StartInterviewRequest;
import com.interview.agent.upper.api.dto.SubmitInterviewAnswerRequest;
import com.interview.agent.upper.domain.InterviewTurnEntity;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.InterviewService;
import com.interview.agent.upper.service.InterviewReportPdfService;
import com.interview.agent.upper.service.UserIdentityResolver;
import com.interview.agent.upper.agent.AgentGateway;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.stream.IntStream;

/** The only public API for text interviews. */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService interviewService;
    private final UserIdentityResolver identity;
    private final InterviewReportPdfService reportPdfService;
    private final AgentGateway agentGateway;

    public InterviewController(InterviewService interviewService, UserIdentityResolver identity,
                               InterviewReportPdfService reportPdfService, AgentGateway agentGateway) {
        this.interviewService = interviewService;
        this.identity = identity;
        this.reportPdfService = reportPdfService;
        this.agentGateway = agentGateway;
    }

    @PostMapping
    public ApiResult<InterviewView> start(@Valid @RequestBody StartInterviewRequest request,
                                          @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(interviewService.start(identity.require(userId), request));
    }

    @GetMapping
    public ApiResult<List<InterviewView>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(interviewService.list(identity.require(userId)));
    }

    @GetMapping("/{sessionId}")
    public ApiResult<InterviewDetailView> get(@PathVariable String sessionId,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(detail(sessionId, identity.require(userId)));
    }

    @GetMapping("/{sessionId}/agent-status")
    public ApiResult<java.util.Map<String, Object>> agentStatus(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        detail(sessionId, identity.require(userId));
        return ApiResult.success(agentGateway.sessionProgress(sessionId));
    }

    @GetMapping("/unfinished/{resumeId}")
    public ApiResult<InterviewView> unfinished(@PathVariable String resumeId,
                                                 @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(interviewService.findUnfinished(identity.require(userId), resumeId));
    }

    @PostMapping("/{sessionId}/answers")
    public ApiResult<InterviewDetailView> submitAnswer(@PathVariable String sessionId,
                                                   @Valid @RequestBody SubmitInterviewAnswerRequest request,
                                                   @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = identity.require(userId);
        interviewService.submitAnswer(sessionId, owner, request.answer(), request.runId());
        return ApiResult.success(detail(sessionId, owner));
    }

    @PostMapping("/{sessionId}/complete")
    public ApiResult<Void> complete(@PathVariable String sessionId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {
        interviewService.complete(sessionId, identity.require(userId));
        return ApiResult.success(null);
    }

    @PostMapping("/{sessionId}/pause")
    public ApiResult<Void> pause(@PathVariable String sessionId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId) {
        interviewService.pause(sessionId, identity.require(userId));
        return ApiResult.success(null);
    }

    @GetMapping("/{sessionId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String sessionId,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId) {
        InterviewDetailView detail = detail(sessionId, identity.require(userId));
        byte[] content = reportPdfService.render(sessionId, detail.session().status(),
                detail.session().totalQuestions(), detail.turns(), detail.session().finalEvaluation());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"interview-" + sessionId + ".pdf\"")
                .body(content);
    }

    @DeleteMapping("/{sessionId}")
    public ApiResult<Void> delete(@PathVariable String sessionId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId) {
        interviewService.delete(sessionId, identity.require(userId));
        return ApiResult.success(null);
    }

    private InterviewDetailView detail(String sessionId, String userId) {
        InterviewView session = interviewService.view(sessionId);
        if (!userId.equals(session.userId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        List<InterviewTurnEntity> turns = interviewService.turns(sessionId);
        List<InterviewTurnView> visibleTurns = IntStream.range(0, turns.size())
                .mapToObj(index -> {
                    InterviewTurnEntity turn = turns.get(index);
                    return new InterviewTurnView(index, turn.getStage(), turn.getQuestion(),
                            turn.getCandidateAnswer(), turn.getEvaluationSummary(),
                            turn.getScore(), turn.getCreatedAt());
                }).toList();
        return new InterviewDetailView(session, visibleTurns);
    }
}
