package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.ResumeAnalysisView;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import com.interview.agent.upper.repository.InterviewSessionRepository;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.ResumeAnalysisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.apache.tika.Tika;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final Tika tika = new Tika();
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final ResumeAnalysisService analysisService;
    private final String demoUserId;

    public ResumeController(
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            InterviewSessionRepository interviewSessionRepository,
            ResumeAnalysisService analysisService,
            @Value("${agent.demo-user-id:demo-user}") String demoUserId) {
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.interviewSessionRepository = interviewSessionRepository;
        this.analysisService = analysisService;
        this.demoUserId = demoUserId;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, String>> health() {
        return ApiResult.success(Map.of("status", "UP", "service", "resume"));
    }

    @PostMapping(
            value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false) String userId)
            throws IOException {
        String effectiveUserId = userId == null || userId.isBlank() ? demoUserId : userId;
        String resumeId = Long.toString(System.currentTimeMillis());
        String candidateId = "candidate-" + resumeId;
        candidateRepository.save(new CandidateEntity(candidateId, effectiveUserId, "默认候选人"));
        String resumeText;
        try {
            resumeText = tika.parseToString(file.getInputStream());
        } catch (Exception error) {
            throw new BusinessException("RESUME_PARSE_FAILED", "简历文件解析失败");
        }
        resumeRepository.save(new ResumeEntity(resumeId, candidateId, 1, resumeText));
        ResumeAnalysisView analysisView = analysisService.submit(resumeId, effectiveUserId);

        Map<String, Object> storage = new HashMap<>();
        storage.put("fileKey", resumeId + "/" + file.getOriginalFilename());
        storage.put("fileUrl", "");
        storage.put("resumeId", Long.parseLong(resumeId));
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("originalText", resumeRepository.findById(resumeId).orElseThrow().getContent());
        analysis.put("summary", "简历评价任务已提交");
        analysis.put("status", analysisView.status());
        analysis.put("analysisId", analysisView.id());
        Map<String, Object> result = new HashMap<>();
        result.put("storage", storage);
        result.put("analysis", analysis);
        result.put("duplicate", false);
        return ApiResult.success(result);
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.success(resumeRepository.findAll().stream().map(resume -> {
            Map<String, Object> item = new HashMap<>();
            ResumeAnalysisView latest = analysisService.latest(resume.getId());
            item.put("id", Long.parseLong(resume.getId()));
            item.put("filename", resume.getId());
            item.put("fileSize", resume.getContent().length());
            item.put("resumeText", resume.getContent());
            item.put("interviewCount", 0);
            item.put("uploadedAt", "");
            item.put("accessCount", 0);
            item.put("latestScore", latest == null ? null : latest.overallScore());
            item.put("lastAnalyzedAt", latest == null ? null : latest.analyzedAt());
            item.put("analyzeStatus", latest == null ? null : latest.status());
            item.put("analyzeError", latest == null ? null : latest.error());
            return item;
        }).toList());
    }

    @GetMapping("/{id}/detail")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("简历不存在"));
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.parseLong(resume.getId()));
        result.put("filename", resume.getId());
        result.put("fileSize", resume.getContent().length());
        result.put("contentType", "text/plain");
        result.put("storageUrl", "");
        result.put("uploadedAt", "");
        result.put("accessCount", 0);
        result.put("resumeText", resume.getContent());
        result.put("interviews", List.of());
        result.put("analyses", analysisService.list(id));
        return ApiResult.success(result);
    }

    /**
     * 保留原 React 导出入口。首期导出 UTF-8 文本，避免在示例项目中伪造生产级 PDF。
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "简历不存在"));
        ResumeAnalysisView latest = analysisService.latest(id);
        StringBuilder content = new StringBuilder()
                .append("Resume\n\n")
                .append(resume.getContent() == null ? "" : resume.getContent())
                .append("\n\nAnalysis\n");
        if (latest == null) {
            content.append("尚未产生分析结果\n");
        } else {
            content.append("status: ").append(latest.status()).append('\n')
                    .append("overallScore: ").append(latest.overallScore()).append('\n')
                    .append("summary: ").append(latest.summary() == null ? "" : latest.summary()).append('\n')
                    .append("strengths: ").append(String.join("; ", latest.strengths())).append('\n')
                    .append("suggestions: ").append(String.join("; ", latest.suggestions())).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"resume-" + id + ".txt\"")
                .body(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/reanalyze")
    public ApiResult<ResumeAnalysisView> reanalyze(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(analysisService.submit(id, effectiveUser(userId)));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "简历不存在"));
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "候选人不存在"));
        if (!candidate.getUserId().equals(effectiveUser(userId))) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "无权删除该简历");
        }
        analysisService.deleteByResumeId(id);
        resumeRepository.delete(resume);
        return ApiResult.success(null);
    }

    @GetMapping("/statistics")
    public ApiResult<Map<String, Object>> statistics() {
        return ApiResult.success(Map.of(
                "totalCount", resumeRepository.count(),
                "totalInterviewCount", interviewSessionRepository.count(),
                "totalAccessCount", 0L));
    }

    private String effectiveUser(String userId) {
        return userId == null || userId.isBlank() ? demoUserId : userId;
    }
}
