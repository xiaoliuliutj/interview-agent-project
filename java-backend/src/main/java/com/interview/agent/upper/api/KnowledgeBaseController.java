package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.KnowledgeBaseQueryRequest;
import com.interview.agent.upper.api.dto.KnowledgeBaseQueryResponse;
import com.interview.agent.upper.api.dto.KnowledgeBaseView;
import com.interview.agent.upper.service.KnowledgeBaseService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/knowledgebase")
public class KnowledgeBaseController {
    private static final long SSE_TIMEOUT_MILLIS = 65_000L;

    private final KnowledgeBaseService service;
    private final Executor executor;

    public KnowledgeBaseController(
            KnowledgeBaseService service,
            @Qualifier("interviewTaskExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String name,
            @RequestPart(value = "category", required = false) String category,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        KnowledgeBaseView view = service.upload(file, name, category, userId);
        return ApiResult.success(Map.of(
                "knowledgeBase", Map.of(
                        "id", view.id(), "name", view.name(), "category", view.category() == null ? "" : view.category(),
                        "fileSize", view.fileSize(), "contentLength", file.getSize()),
                "storage", Map.of("fileKey", view.id() + "/" + view.originalFilename(), "fileUrl", ""),
                "duplicate", false));
    }

    @GetMapping("/list")
    public ApiResult<List<KnowledgeBaseView>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.list(userId));
    }

    @GetMapping("/{id}")
    public ApiResult<KnowledgeBaseView> get(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.get(id, userId));
    }

    /** 保留前端下载入口，直接返回 Java 上层持久化的原始文本内容。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        KnowledgeBaseService.DownloadedDocument document = service.download(id, userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.filename() + "\"")
                .body(document.content());
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        service.delete(id, userId);
        return ApiResult.success(null);
    }

    @GetMapping("/categories")
    public ApiResult<List<String>> categories(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.categories(userId));
    }

    @GetMapping("/category/{category}")
    public ApiResult<List<KnowledgeBaseView>> byCategory(@PathVariable String category,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.byCategory(category, userId));
    }

    @GetMapping("/uncategorized")
    public ApiResult<List<KnowledgeBaseView>> uncategorized(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.uncategorized(userId));
    }

    @PutMapping("/{id}/category")
    public ApiResult<Void> updateCategory(@PathVariable long id, @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        service.updateCategory(id, body.get("category"), userId);
        return ApiResult.success(null);
    }

    @GetMapping("/search")
    public ApiResult<List<KnowledgeBaseView>> search(@RequestParam String keyword,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.search(keyword, userId));
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        List<KnowledgeBaseView> items = service.list(userId);
        return ApiResult.success(Map.of(
                "totalCount", items.size(),
                "totalQuestionCount", items.stream().mapToLong(KnowledgeBaseView::questionCount).sum(),
                "totalAccessCount", items.stream().mapToLong(KnowledgeBaseView::accessCount).sum(),
                "completedCount", items.stream().filter(item -> "COMPLETED".equals(item.vectorStatus())).count(),
                "processingCount", items.stream().filter(item -> "PROCESSING".equals(item.vectorStatus())).count()));
    }

    @PostMapping("/{id}/revectorize")
    public ApiResult<Void> revectorize(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        service.revectorize(id, userId);
        return ApiResult.success(null);
    }

    @PostMapping("/query")
    public ApiResult<KnowledgeBaseQueryResponse> query(@RequestBody KnowledgeBaseQueryRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.query(request, userId));
    }

    /** 保留原 React 的流式入口；当前下层检索完成后发送单个 SSE 结果。 */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@RequestBody KnowledgeBaseQueryRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        executor.execute(() -> {
            try {
                String answer = service.query(request, userId).answer();
                emitter.send(SseEmitter.event().name("message")
                        .data(answer.replace("\n", "\\n").replace("\r", "\\r")));
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
