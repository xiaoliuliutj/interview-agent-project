package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.LegacyCreateInterviewRequest;
import com.interview.agent.upper.api.dto.LegacyCurrentQuestion;
import com.interview.agent.upper.api.dto.LegacyInterviewSession;
import com.interview.agent.upper.api.dto.LegacyInterviewListItem;
import com.interview.agent.upper.api.dto.LegacySubmitAnswerRequest;
import com.interview.agent.upper.api.dto.LegacySubmitAnswerResponse;
import com.interview.agent.upper.service.LegacyInterviewFacade;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 保留原 React 面试 API 的协议适配器。 */
@RestController
@RequestMapping("/api/interview/sessions")
public class LegacyInterviewController {
    private final LegacyInterviewFacade facade;

    public LegacyInterviewController(LegacyInterviewFacade facade) {
        this.facade = facade;
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
    public ApiResult<LegacyInterviewSession> get(@PathVariable String sessionId) {
        return ApiResult.success(facade.get(sessionId));
    }

    @GetMapping("/{sessionId}/question")
    public ApiResult<LegacyCurrentQuestion> question(@PathVariable String sessionId) {
        return ApiResult.success(facade.currentQuestion(sessionId));
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
            @RequestHeader(value = "X-Run-Id", required = false) String runId) {
        return ApiResult.success(facade.submit(sessionId, request.answer(), runId));
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
    public ApiResult<Map<String, Object>> report(@PathVariable String sessionId) {
        LegacyInterviewSession session = facade.get(sessionId);
        List<Map<String, Object>> turns = facade.turns(sessionId);
        return ApiResult.success(Map.of(
                "sessionId", session.sessionId(),
                "totalQuestions", session.totalQuestions(),
                "overallScore", 0,
                "overallFeedback", "面试评价将在全部阶段完成后生成",
                "questionDetails", turns,
                "categoryScores", List.of(),
                "strengths", List.of(),
                "improvements", List.of(),
                "referenceAnswers", List.of()));
    }

    @GetMapping("/{sessionId}/details")
    public ApiResult<Map<String, Object>> details(@PathVariable String sessionId) {
        LegacyInterviewSession session = facade.get(sessionId);
        return ApiResult.success(Map.of(
                "sessionId", session.sessionId(),
                "totalQuestions", session.totalQuestions(),
                "status", session.status(),
                "answers", facade.turns(sessionId)));
    }

    /**
     * 保留原 React 导出入口。首期输出可下载的 UTF-8 文本，不宣称生成生产级 PDF。
     */
    @GetMapping("/{sessionId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String sessionId) {
        LegacyInterviewSession session = facade.get(sessionId);
        StringBuilder content = new StringBuilder()
                .append("Interview ").append(session.sessionId()).append('\n')
                .append("status: ").append(session.status()).append('\n')
                .append("totalQuestions: ").append(session.totalQuestions()).append("\n\n");
        for (Map<String, Object> turn : facade.turns(sessionId)) {
            content.append("Question ").append(turn.get("questionIndex")).append('\n')
                    .append(String.valueOf(turn.get("question"))).append('\n')
                    .append("Answer: ").append(String.valueOf(turn.get("userAnswer"))).append('\n')
                    .append("Feedback: ").append(String.valueOf(turn.get("feedback"))).append("\n\n");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"interview-" + sessionId + ".txt\"")
                .body(content.toString().getBytes(StandardCharsets.UTF_8));
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
