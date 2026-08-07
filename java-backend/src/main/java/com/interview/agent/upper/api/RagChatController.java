package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.CreateRagChatSessionRequest;
import com.interview.agent.upper.api.dto.RagChatKnowledgeBasesRequest;
import com.interview.agent.upper.api.dto.RagChatMessageRequest;
import com.interview.agent.upper.api.dto.RagChatSessionDetailView;
import com.interview.agent.upper.api.dto.RagChatSessionListItemView;
import com.interview.agent.upper.api.dto.RagChatSessionView;
import com.interview.agent.upper.api.dto.RagChatTitleRequest;
import com.interview.agent.upper.service.RagChatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

/** 兼容原 React 知识库问答页面的上层接口。 */
@RestController
@RequestMapping("/api/rag-chat/sessions")
public class RagChatController {
    private static final long SSE_TIMEOUT_MILLIS = 65_000L;

    private final RagChatService service;
    private final Executor executor;

    public RagChatController(
            RagChatService service,
            @Qualifier("interviewTaskExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    @PostMapping
    public ApiResult<RagChatSessionView> create(@Valid @RequestBody CreateRagChatSessionRequest request) {
        return ApiResult.success(service.create(request.knowledgeBaseIds(), request.title()));
    }

    @GetMapping
    public ApiResult<List<RagChatSessionListItemView>> list() {
        return ApiResult.success(service.list());
    }

    @GetMapping("/{sessionId}")
    public ApiResult<RagChatSessionDetailView> detail(@PathVariable Long sessionId) {
        return ApiResult.success(service.detail(sessionId));
    }

    @PutMapping("/{sessionId}/title")
    public ApiResult<Void> updateTitle(
            @PathVariable Long sessionId, @Valid @RequestBody RagChatTitleRequest request) {
        service.updateTitle(sessionId, request.title());
        return ApiResult.success(null);
    }

    @PutMapping("/{sessionId}/knowledge-bases")
    public ApiResult<Void> updateKnowledgeBases(
            @PathVariable Long sessionId, @Valid @RequestBody RagChatKnowledgeBasesRequest request) {
        service.updateKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return ApiResult.success(null);
    }

    @PutMapping("/{sessionId}/pin")
    public ApiResult<Void> togglePin(@PathVariable Long sessionId) {
        service.togglePin(sessionId);
        return ApiResult.success(null);
    }

    @DeleteMapping("/{sessionId}")
    public ApiResult<Void> delete(@PathVariable Long sessionId) {
        service.delete(sessionId);
        return ApiResult.success(null);
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(
            @PathVariable Long sessionId, @Valid @RequestBody RagChatMessageRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        executor.execute(() -> {
            try {
                String answer = service.answer(sessionId, request.question());
                // 前端按 SSE data 行自行还原换行，因此固定发送单行 payload。
                emitter.send(SseEmitter.event().name("message").data(answer.replace("\n", "\\n").replace("\r", "\\r")));
                emitter.complete();
            } catch (Exception error) {
                try {
                    emitter.send(SseEmitter.event().name("message").data("RAG 查询失败，请稍后重试。"));
                    emitter.complete();
                } catch (IOException ignored) {
                    emitter.completeWithError(error);
                }
            }
        });
        return emitter;
    }
}
