package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.KnowledgeBaseView;
import com.interview.agent.upper.service.KnowledgeBaseService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledgebase")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
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
                        "fileSize", view.fileSize(), "contentLength", file.getSize())));
    }

    @GetMapping("/list")
    public ApiResult<List<KnowledgeBaseView>> list(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.list(userId, sortBy, vectorStatus));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        KnowledgeBaseService.DownloadedDocument document = service.download(id, userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.filename() + "\"")
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
                "completedCount", items.stream().filter(item -> "COMPLETED".equals(item.vectorStatus())).count(),
                "processingCount", items.stream().filter(item -> "PROCESSING".equals(item.vectorStatus())
                        || "PENDING".equals(item.vectorStatus())).count(),
                "failedCount", items.stream().filter(item -> "FAILED".equals(item.vectorStatus())
                        || "DELETE_FAILED".equals(item.vectorStatus())).count()));
    }

    @PostMapping("/{id}/revectorize")
    public ApiResult<Void> revectorize(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        service.revectorize(id, userId);
        return ApiResult.success(null);
    }
}
