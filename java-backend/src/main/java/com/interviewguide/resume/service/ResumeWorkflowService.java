package com.interviewguide.resume.service;

import com.interviewguide.resume.domain.ResumeAnalysisResponse;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateMapper;
import com.interviewguide.interview.mapper.InterviewSessionMapper;
import com.interviewguide.interview.mapper.InterviewTurnMapper;
import com.interviewguide.resume.mapper.ResumeMapper;
import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.utils.id.BusinessIdGenerator;
import com.interviewguide.utils.file.ResumeFileStorageUtil;
import com.interviewguide.utils.pdf.ResumePdfUtil;
import com.interviewguide.utils.file.DocumentContentUtil;
import com.interviewguide.common.security.UserIdentityResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Coordinates full resume business workflows for the endpoint-specific resume services. */
@Service
public class ResumeWorkflowService {
    /** Reads and writes versioned resume entities. */
    private final ResumeMapper resumeMapper;
    /** Creates and updates candidates that own resume versions. */
    private final CandidateMapper candidateMapper;
    /** Reads and removes interview sessions linked to deleted resumes. */
    private final InterviewSessionMapper interviewSessionMapper;
    /** Removes answer turns linked to deleted interview sessions. */
    private final InterviewTurnMapper interviewTurnMapper;
    /** Creates and reads asynchronous resume-analysis tasks. */
    private final ResumeAnalysisService analysisService;
    /** Stores and retrieves original uploaded file bytes. */
    private final ResumeFileStorageUtil fileStorage;
    /** Resolves the request user to the durable owner identifier. */
    private final UserIdentityResolver identity;
    /** Generates durable business identifiers for newly stored resumes and candidates. */
    private final BusinessIdGenerator idGenerator;
    /** Points to the configured CJK font used by resume PDF exports. */
    private final Path pdfFontPath;
    /** Resolves caller-owned resumes and their candidate relationship. */
    private final ResumeResourceService access;
    /** Projects interview data embedded in a resume detail response. */
    private final ResumeInterviewResponseService responseUtil;

    /** Injects every dependency required by complete resume lifecycle workflows. */
    public ResumeWorkflowService(
            ResumeMapper resumeMapper,
            CandidateMapper candidateMapper,
            InterviewSessionMapper interviewSessionMapper,
            InterviewTurnMapper interviewTurnMapper,
            ResumeAnalysisService analysisService,
            ResumeFileStorageUtil fileStorage,
            UserIdentityResolver identity,
            BusinessIdGenerator idGenerator,
            @Value("${agent.pdf-font-path}") String pdfFontPath,
            ResumeResourceService access,
            ResumeInterviewResponseService responseUtil) {
        this.resumeMapper = resumeMapper;
        this.candidateMapper = candidateMapper;
        this.interviewSessionMapper = interviewSessionMapper;
        this.interviewTurnMapper = interviewTurnMapper;
        this.analysisService = analysisService;
        this.fileStorage = fileStorage;
        this.identity = identity;
        this.idGenerator = idGenerator;
        this.pdfFontPath = pdfFontPath == null || pdfFontPath.isBlank() ? null : Path.of(pdfFontPath);
        this.access = access;
        this.responseUtil = responseUtil;
    }
    /** Stores an upload, updates the candidate's current version and creates its analysis task. */
    public Map<String, Object> upload(
            MultipartFile file,
            String targetRole,
            String userId) throws IOException {
        String owner = identity.require(userId);
        if (file.isEmpty()) throw new BusinessException("RESUME_FILE_REQUIRED", "resume file must not be empty");
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new BusinessException("RESUME_FILENAME_REQUIRED", "resume filename is required");
        }
        if (targetRole == null || targetRole.isBlank()) {
            throw new BusinessException("TARGET_ROLE_REQUIRED", "targetRole is required for resume analysis");
        }
        ResumeFileStorageUtil.FileDescriptor descriptor = fileStorage.inspect(file);
        CandidateEntity candidate = candidateMapper.findByUserId(owner)
                .orElseGet(() -> candidateMapper.save(
                        new CandidateEntity(idGenerator.next(), owner, owner)));
        ResumeEntity duplicate = resumeMapper
                .findFirstByCandidateIdAndFileHash(candidate.getId(), descriptor.hash())
                .orElse(null);
        if (duplicate != null) {
            List<String> candidateResumeIds = resumeMapper.findByCandidateId(candidate.getId()).stream()
                    .map(ResumeEntity::getId).toList();
            candidate.setCurrentResumeId(duplicate.getId());
            candidateMapper.save(candidate);
            analysisService.cancelActiveForResumeIds(candidateResumeIds);
            ResumeAnalysisResponse analysis = analysisService.submit(duplicate.getId(), owner, targetRole);
            return Map.of(
                    "duplicate", true,
                    "resumeId", duplicate.getId(),
                    "storage", Map.of("resumeId", duplicate.getId()),
                    "analysis", Map.of("originalText", duplicate.getContent(), "status", analysis.status(),
                            "analysisId", analysis.id()));
        }
        List<String> previousResumeIds = resumeMapper.findByCandidateId(candidate.getId()).stream()
                .map(ResumeEntity::getId).toList();
        int nextVersion = resumeMapper.findFirstByCandidateIdOrderByVersionDesc(candidate.getId())
                .map(item -> item.getVersion() + 1).orElse(1);
        String resumeId = idGenerator.next();
        String text = DocumentContentUtil.extractText(file, file.getOriginalFilename());
        if (text == null || text.isBlank()) {
            throw new BusinessException("RESUME_CONTENT_EMPTY", "resume text must not be empty");
        }
        ResumeFileStorageUtil.StoredFile stored = fileStorage.store(descriptor, resumeId);
        try {
            ResumeEntity resume = new ResumeEntity(resumeId, candidate.getId(), nextVersion, text);
            resume.attachFile(stored.hash(), stored.filename(), stored.size(), stored.contentType(), stored.key());
            resumeMapper.save(resume);
        } catch (RuntimeException error) {
            fileStorage.delete(stored.key());
            throw error;
        }
        analysisService.cancelActiveForResumeIds(previousResumeIds);
        candidate.setCurrentResumeId(resumeId);
        candidateMapper.save(candidate);
        ResumeAnalysisResponse analysis = analysisService.submit(resumeId, owner, targetRole);
        Map<String, Object> result = new HashMap<>();
        result.put("storage", Map.of("resumeId", resumeId));
        result.put("analysis", Map.of("originalText", text, "status", analysis.status(), "analysisId", analysis.id()));
        return result;
    }

    /** Lists all caller-owned resumes with latest analysis and interview counts. */
    public List<Map<String, Object>> list(
            String userId) {
        String owner = identity.require(userId);
        return resumeMapper.findAll().stream().filter(item -> access.owns(item, owner)).map(resume -> {
            ResumeAnalysisResponse latest = analysisService.latest(resume.getId());
            Map<String, Object> item = new HashMap<>();
            item.put("id", resume.getId());
            item.put("filename", resume.getOriginalFilename());
            item.put("fileSize", resume.getFileSize());
            item.put("resumeText", resume.getContent());
            item.put("interviewCount", interviewSessionMapper.findByUserIdOrderByCreatedAtDesc(owner).stream()
                    .filter(session -> resume.getId().equals(session.getResumeId())).count());
            item.put("uploadedAt", resume.getCreatedAt());
            item.put("latestScore", latest == null ? null : latest.overallScore());
            item.put("lastAnalyzedAt", latest == null ? null : latest.analyzedAt());
            item.put("analyzeStatus", latest == null ? null : latest.status());
            item.put("analyzeError", latest == null ? null : latest.error());
            return item;
        }).toList();
    }
    /** Returns a caller-owned resume with related interviews and analysis history. */
    public Map<String, Object> detail(String id,
            String userId) {
        ResumeEntity resume = access.owned(id, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("id", resume.getId());
        result.put("filename", resume.getOriginalFilename());
        result.put("fileSize", resume.getFileSize());
        result.put("contentType", resume.getContentType());
        result.put("uploadedAt", resume.getCreatedAt());
        result.put("resumeText", resume.getContent());
        result.put("interviews", interviewSessionMapper
                .findByUserIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .filter(session -> resume.getId().equals(session.getResumeId()))
                .map(responseUtil::interview)
                .toList());
        result.put("analyses", analysisService.list(id));
        return result;
    }
    /** Renders a caller-owned resume and latest analysis to a PDF response. */
    public ResponseEntity<byte[]> export(String id,
            String userId) {
        ResumeEntity resume = access.owned(id, userId);
        ResumeAnalysisResponse analysis = analysisService.latest(id);
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
            ResumePdfUtil.addTextPages(document, font, content.toString());
            document.save(output);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume-" + id + ".pdf\"")
                    .body(output.toByteArray());
        } catch (IOException error) {
            throw new BusinessException("RESUME_PDF_EXPORT_FAILED", "unable to export PDF report");
        }
    }

    /** Returns original uploaded file bytes for a caller-owned resume. */
    public ResponseEntity<byte[]> download(String id,
            String userId) throws IOException {
        ResumeEntity resume = access.owned(id, userId);
        String contentType = resume.getContentType() == null || resume.getContentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : resume.getContentType();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resume.getOriginalFilename() + "\"")
                .body(fileStorage.read(resume.getStorageKey()));
    }
    /** Submits a new analysis task when the requested resume is the candidate's current version. */
    public ResumeAnalysisResponse reanalyze(String id,
            String targetRole,
            String userId) {
        String owner = identity.require(userId);
        ResumeEntity resume = access.owned(id, owner);
        CandidateEntity candidate = candidateMapper.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
        if (!id.equals(candidate.getCurrentResumeId())) {
            throw new BusinessException("RESUME_NOT_CURRENT",
                    "only the current resume can be reanalyzed");
        }
        return analysisService.submit(id, owner, targetRole);
    }
    /** Cancels analysis, removes linked interviews and deletes the original stored resume. */
    public void delete(String id,
            String userId) throws IOException {
        ResumeEntity resume = access.owned(id, userId);
        CandidateEntity candidate = candidateMapper.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
        // A queued message can still arrive after deletion. Mark the task cancelled before
        // deleting its record so the worker will never apply an old result.
        analysisService.cancelActiveForResumeIds(List.of(id));
        analysisService.deleteByResumeId(id);
        interviewSessionMapper.findByUserIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .filter(session -> id.equals(session.getResumeId()))
                .forEach(session -> {
                    interviewTurnMapper.deleteBySessionId(session.getId());
                    interviewSessionMapper.delete(session);
                });
        if (id.equals(candidate.getCurrentResumeId())) {
            String replacementResumeId = resumeMapper.findByCandidateId(candidate.getId()).stream()
                    .filter(item -> !id.equals(item.getId()))
                    .max(java.util.Comparator.comparingInt(ResumeEntity::getVersion))
                    .map(ResumeEntity::getId)
                    .orElse(null);
            candidate.setCurrentResumeId(replacementResumeId);
            candidateMapper.save(candidate);
        }
        fileStorage.delete(resume.getStorageKey());
        resumeMapper.delete(resume);
        return;
    }

}
