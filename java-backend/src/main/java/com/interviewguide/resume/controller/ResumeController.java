package com.interviewguide.resume.controller;

import com.interviewguide.common.web.ApiResult;
import com.interviewguide.resume.domain.ResumeAnalysisResponse;
import com.interviewguide.resume.service.ResumeDeleteService;
import com.interviewguide.resume.service.ResumeDetailService;
import com.interviewguide.resume.service.ResumeDownloadService;
import com.interviewguide.resume.service.ResumeExportService;
import com.interviewguide.resume.service.ResumeListService;
import com.interviewguide.resume.service.ResumeReanalyzeService;
import com.interviewguide.resume.service.ResumeUploadService;
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

/** HTTP adapter for the resume module; every endpoint delegates to its matching application service. */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    /** Handles uploaded file validation, persistence, and analysis scheduling. */
    private final ResumeUploadService uploadService;
    /** Handles caller-visible resume listing. */
    private final ResumeListService listService;
    /** Handles one resume's detail query. */
    private final ResumeDetailService detailService;
    /** Handles generated resume analysis exports. */
    private final ResumeExportService exportService;
    /** Handles original resume file downloads. */
    private final ResumeDownloadService downloadService;
    /** Handles a new analysis run for an existing resume. */
    private final ResumeReanalyzeService reanalyzeService;
    /** Handles removal of one caller-owned resume. */
    private final ResumeDeleteService deleteService;

    /** Injects exactly one endpoint service for each resume route. */
    public ResumeController(ResumeUploadService uploadService, ResumeListService listService,
                            ResumeDetailService detailService, ResumeExportService exportService,
                            ResumeDownloadService downloadService, ResumeReanalyzeService reanalyzeService,
                            ResumeDeleteService deleteService) {
        this.uploadService = uploadService;
        this.listService = listService;
        this.detailService = detailService;
        this.exportService = exportService;
        this.downloadService = downloadService;
        this.reanalyzeService = reanalyzeService;
        this.deleteService = deleteService;
    }

    @PostMapping("/upload")
    /** Stores an uploaded resume and starts its asynchronous analysis. */
    public ApiResult<Map<String, Object>> upload(@RequestPart("file") MultipartFile file,
            @RequestPart("targetRole") String targetRole,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        return ApiResult.success(uploadService.upload(file, targetRole, userId));
    }

    @GetMapping
    /** Lists all resumes that belong to the caller. */
    public ApiResult<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(listService.list(userId));
    }

    @GetMapping("/{id}/detail")
    /** Returns a resume and its latest analysis state. */
    public ApiResult<Map<String, Object>> detail(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(detailService.detail(id, userId));
    }

    @GetMapping("/{id}/export")
    /** Returns a PDF export for the caller-owned resume. */
    public ResponseEntity<byte[]> export(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return exportService.export(id, userId);
    }

    @GetMapping("/{id}/download")
    /** Returns the exact stored source file for the caller-owned resume. */
    public ResponseEntity<byte[]> download(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        return downloadService.download(id, userId);
    }

    @PostMapping("/{id}/reanalyze")
    /** Enqueues a fresh analysis request using the supplied target role. */
    public ApiResult<ResumeAnalysisResponse> reanalyze(@PathVariable String id, @RequestParam("targetRole") String targetRole,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(reanalyzeService.reanalyze(id, targetRole, userId));
    }

    @DeleteMapping("/{id}")
    /** Deletes the caller-owned resume together with dependent artifacts. */
    public ApiResult<Void> delete(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        deleteService.delete(id, userId);
        return ApiResult.success(null);
    }
}
