package com.interviewguide.resume.controller;

import com.interviewguide.common.web.dto.ApiResult;
import com.interviewguide.resume.dto.ResumeAnalysisView;
import com.interviewguide.resume.service.ResumeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** HTTP adapter for the resume module. Business and persistence work live in ResumeService. */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/upload")
    public ApiResult<Map<String, Object>> upload(@RequestPart("file") MultipartFile file,
            @RequestPart("targetRole") String targetRole,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        return ApiResult.success(resumeService.upload(file, targetRole, userId));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(resumeService.list(userId));
    }

    @GetMapping("/{id}/detail")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(resumeService.detail(id, userId));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return resumeService.export(id, userId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        return resumeService.download(id, userId);
    }

    @PostMapping("/{id}/reanalyze")
    public ApiResult<ResumeAnalysisView> reanalyze(@PathVariable String id, @RequestParam("targetRole") String targetRole,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(resumeService.reanalyze(id, targetRole, userId));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        resumeService.delete(id, userId);
        return ApiResult.success(null);
    }
}
