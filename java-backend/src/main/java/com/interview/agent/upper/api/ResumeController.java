package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.ResumeAnalysisView;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.InterviewSessionRepository;
import com.interview.agent.upper.repository.InterviewTurnRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.BusinessIdGenerator;
import com.interview.agent.upper.service.ResumeAnalysisService;
import com.interview.agent.upper.service.ResumeFileStorageService;
import com.interview.agent.upper.service.UserIdentityResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final InterviewTurnRepository interviewTurnRepository;
    private final ResumeAnalysisService analysisService;
    private final ResumeFileStorageService fileStorage;
    private final UserIdentityResolver identity;
    private final BusinessIdGenerator idGenerator;
    private final Path pdfFontPath;

    public ResumeController(
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            InterviewSessionRepository interviewSessionRepository,
            InterviewTurnRepository interviewTurnRepository,
            ResumeAnalysisService analysisService,
            ResumeFileStorageService fileStorage,
            UserIdentityResolver identity,
            BusinessIdGenerator idGenerator,
            @Value("${agent.pdf-font-path}") String pdfFontPath) {
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.interviewSessionRepository = interviewSessionRepository;
        this.interviewTurnRepository = interviewTurnRepository;
        this.analysisService = analysisService;
        this.fileStorage = fileStorage;
        this.identity = identity;
        this.idGenerator = idGenerator;
        this.pdfFontPath = pdfFontPath == null || pdfFontPath.isBlank() ? null : Path.of(pdfFontPath);
    }

    @GetMapping("/health")
    public ApiResult<Map<String, String>> health() {
        return ApiResult.success(Map.of("status", "UP", "service", "resume"));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        String owner = identity.require(userId);
        if (file.isEmpty()) throw new BusinessException("RESUME_FILE_REQUIRED", "resume file must not be empty");
        ResumeFileStorageService.StoredFile stored = fileStorage.store(file);
        ResumeEntity duplicate = resumeRepository.findByFileHash(stored.hash()).orElse(null);
        if (duplicate != null && owns(duplicate, owner)) {
            return ApiResult.success(Map.of("duplicate", true, "resumeId", duplicate.getId(),
                    "storage", Map.of("fileKey", duplicate.getStorageKey())));
        }
        String resumeId = idGenerator.next();
        String candidateId = "candidate-" + resumeId;
        candidateRepository.save(new CandidateEntity(candidateId, owner, "Candidate"));
        String text;
        try {
            text = tika.parseToString(file.getInputStream());
        } catch (Exception error) {
            throw new BusinessException("RESUME_PARSE_FAILED", "unable to parse resume file");
        }
        ResumeEntity resume = new ResumeEntity(resumeId, candidateId, 1, text);
        resume.attachFile(stored.hash(), stored.filename(), stored.size(), stored.contentType(), stored.key());
        resumeRepository.save(resume);
        ResumeAnalysisView analysis = analysisService.submit(resumeId, owner);
        Map<String, Object> result = new HashMap<>();
        result.put("storage", Map.of("fileKey", stored.key(), "resumeId", resumeId));
        result.put("analysis", Map.of("originalText", text, "status", analysis.status(), "analysisId", analysis.id()));
        result.put("duplicate", false);
        return ApiResult.success(result);
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = identity.require(userId);
        return ApiResult.success(resumeRepository.findAll().stream().filter(item -> owns(item, owner)).map(resume -> {
            ResumeAnalysisView latest = analysisService.latest(resume.getId());
            Map<String, Object> item = new HashMap<>();
            item.put("id", resume.getId());
            item.put("filename", resume.getOriginalFilename());
            item.put("fileSize", resume.getFileSize());
            item.put("resumeText", resume.getContent());
            item.put("interviewCount", interviewSessionRepository.findByUserIdOrderByCreatedAtDesc(owner).stream()
                    .filter(session -> resume.getId().equals(session.getResumeId())).count());
            item.put("uploadedAt", resume.getCreatedAt());
            item.put("accessCount", 0);
            item.put("latestScore", latest == null ? null : latest.overallScore());
            item.put("lastAnalyzedAt", latest == null ? null : latest.analyzedAt());
            item.put("analyzeStatus", latest == null ? null : latest.status());
            item.put("analyzeError", latest == null ? null : latest.error());
            return item;
        }).toList());
    }

    @GetMapping("/{id}/detail")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResumeEntity resume = owned(id, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("id", resume.getId());
        result.put("filename", resume.getOriginalFilename());
        result.put("fileSize", resume.getFileSize());
        result.put("contentType", resume.getContentType());
        result.put("storageUrl", resume.getStorageKey());
        result.put("uploadedAt", resume.getCreatedAt());
        result.put("accessCount", 0);
        result.put("resumeText", resume.getContent());
        result.put("interviews", interviewSessionRepository.findByUserIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .filter(session -> resume.getId().equals(session.getResumeId())).toList());
        result.put("analyses", analysisService.list(id));
        return ApiResult.success(result);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ResumeEntity resume = owned(id, userId);
        ResumeAnalysisView analysis = analysisService.latest(id);
        StringBuilder content = new StringBuilder("Resume\n\n").append(resume.getContent() == null ? "" : resume.getContent());
        if (analysis != null) {
            content.append("\n\nAnalysis\nscore: ").append(analysis.overallScore())
                    .append("\nsummary: ").append(analysis.summary() == null ? "" : analysis.summary())
                    .append("\nstrengths: ").append(String.join("; ", analysis.strengths()))
                    .append("\nsuggestions: ").append(String.join("; ", analysis.suggestions()));
        }
        if (pdfFontPath == null || !Files.isRegularFile(pdfFontPath)) {
            throw new BusinessException("RESUME_PDF_FONT_REQUIRED", "AGENT_PDF_FONT_PATH must point to a readable CJK font");
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, pdfFontPath.toFile());
            addTextPages(document, font, content.toString());
            document.save(output);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume-" + id + ".pdf\"")
                    .body(output.toByteArray());
        } catch (IOException error) {
            throw new BusinessException("RESUME_PDF_EXPORT_FAILED", "unable to export PDF report");
        }
    }

    private void addTextPages(PDDocument document, PDType0Font font, String text) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String source : text.split("\\R", -1)) {
            String line = source;
            while (line.length() > 55) { lines.add(line.substring(0, 55)); line = line.substring(55); }
            lines.add(line);
        }
        PDPage page = null;
        PDPageContentStream stream = null;
        int lineCount = 0;
        for (String line : lines) {
            if (stream == null || lineCount >= 48) {
                if (stream != null) { stream.endText(); stream.close(); }
                page = new PDPage(); document.addPage(page);
                stream = new PDPageContentStream(document, page);
                stream.beginText(); stream.setFont(font, 10); stream.setLeading(14); stream.newLineAtOffset(40, 750); lineCount = 0;
            }
            stream.showText(line.replace("\\t", "  ")); stream.newLine(); lineCount++;
        }
        if (stream != null) { stream.endText(); stream.close(); }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        ResumeEntity resume = owned(id, userId);
        String contentType = resume.getContentType() == null || resume.getContentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : resume.getContentType();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resume.getOriginalFilename() + "\"")
                .body(fileStorage.read(resume.getStorageKey()));
    }

    @PostMapping("/{id}/reanalyze")
    public ApiResult<ResumeAnalysisView> reanalyze(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = identity.require(userId); owned(id, owner);
        return ApiResult.success(analysisService.submit(id, owner));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        ResumeEntity resume = owned(id, userId);
        analysisService.deleteByResumeId(id);
        interviewSessionRepository.findByUserIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .filter(session -> id.equals(session.getResumeId()))
                .forEach(session -> {
                    interviewTurnRepository.deleteBySessionId(session.getId());
                    interviewSessionRepository.delete(session);
                });
        fileStorage.delete(resume.getStorageKey());
        resumeRepository.delete(resume);
        return ApiResult.success(null);
    }

    @GetMapping("/statistics")
    public ApiResult<Map<String, Object>> statistics(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = identity.require(userId);
        long count = resumeRepository.findAll().stream().filter(item -> owns(item, owner)).count();
        return ApiResult.success(Map.of("totalCount", count, "totalInterviewCount",
                interviewSessionRepository.findByUserIdOrderByCreatedAtDesc(owner).size(), "totalAccessCount", 0L));
    }

    private ResumeEntity owned(String id, String userId) {
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "resume not found"));
        if (!owns(resume, identity.require(userId))) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "resume does not belong to current user");
        }
        return resume;
    }

    private boolean owns(ResumeEntity resume, String userId) {
        return candidateRepository.findById(resume.getCandidateId())
                .map(candidate -> userId.equals(candidate.getUserId())).orElse(false);
    }
}
