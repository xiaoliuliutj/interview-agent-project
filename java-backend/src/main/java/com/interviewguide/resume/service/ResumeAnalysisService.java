package com.interviewguide.resume.service;

import com.interviewguide.common.exception.BusinessException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.resume.dto.ResumeAnalysisView;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateRepository;
import com.interviewguide.resume.mapper.ResumeRepository;
import com.interviewguide.infrastructure.redis.JavaTaskStatusCache;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.time.Instant;

@Service
public class ResumeAnalysisService {
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisWorker worker;
    private final ObjectMapper objectMapper;
    private final JavaTaskStatusCache taskCache;

    public ResumeAnalysisService(ResumeRepository resumeRepository,
                                 CandidateRepository candidateRepository,
                                 ResumeAnalysisPersistenceService persistence,
                                 ResumeAnalysisWorker worker,
                                 ObjectMapper objectMapper,
                                 JavaTaskStatusCache taskCache) {
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.persistence = persistence;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.taskCache = taskCache;
    }

    public ResumeAnalysisView submit(String resumeId, String userId, String targetRole) {
        if (targetRole == null || targetRole.isBlank()) {
            throw new BusinessException("TARGET_ROLE_REQUIRED", "targetRole is required for resume analysis");
        }
        ResumeEntity resume = requiredResume(resumeId);
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
        if (!userId.equals(candidate.getUserId())) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "resume does not belong to current user");
        }
        // Reanalysis of the same current resume replaces the previous pending run.
        // Keeping two runnable tasks for one resume would allow a slower response to
        // overwrite a newer result for the same target role.
        persistence.cancelActiveForResumeIds(List.of(resumeId));
        ResumeAnalysisEntity analysis = persistence.create(resumeId, targetRole.strip());
        try {
            worker.enqueue(analysis.getId(), userId);
            return toView(analysis);
        } catch (RuntimeException error) {
            persistence.fail(analysis.getId(), safeMessage(error));
            throw error;
        }
    }

    public ResumeAnalysisView latest(String resumeId) {
        // Polling clients normally observe Redis first. PostgreSQL is used only
        // after expiry, a Redis fault, or for historical records not cached yet.
        var cached = taskCache.latestResumeAnalysis(resumeId);
        if (cached.isPresent()) return toCachedView(cached.get());
        ResumeAnalysisEntity analysis = persistence.latest(resumeId);
        return analysis == null ? null : toView(analysis);
    }

    public List<ResumeAnalysisView> list(String resumeId) {
        return persistence.list(resumeId).stream().map(this::toView).toList();
    }

    public void deleteByResumeId(String resumeId) { persistence.deleteByResumeId(resumeId); }

    public void cancelActiveForResumeIds(List<String> resumeIds) {
        persistence.cancelActiveForResumeIds(resumeIds);
    }

    private ResumeEntity requiredResume(String resumeId) {
        return resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "resume not found"));
    }

    private ResumeAnalysisView toView(ResumeAnalysisEntity entity) {
        return new ResumeAnalysisView(entity.getId(), entity.getStatus(), entity.getOverallScore(),
                entity.getContentScore(), entity.getStructureScore(), entity.getSkillMatchScore(),
                entity.getExpressionScore(), entity.getProjectScore(), entity.getSummary(), entity.getUpdatedAt(),
                stringList(entity.getStrengthsJson()), stringList(entity.getSuggestionsJson()),
                mapList(entity.getIssuesJson()), entity.getError());
    }

    private ResumeAnalysisView toCachedView(Map<String, Object> value) {
        return new ResumeAnalysisView(
                number(value.get("analysisId")).longValue(), string(value.get("status")),
                integerOrNull(value.get("overallScore")), integerOrNull(value.get("contentScore")),
                integerOrNull(value.get("structureScore")), integerOrNull(value.get("skillMatchScore")),
                integerOrNull(value.get("expressionScore")), integerOrNull(value.get("projectScore")),
                nullableString(value.get("summary")), parseInstant(value.get("updatedAt")),
                stringList(nullableString(value.get("strengthsJson"))),
                stringList(nullableString(value.get("suggestionsJson"))),
                mapList(nullableString(value.get("issuesJson"))), nullableString(value.get("error")));
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : 0L;
    }

    private static Integer integerOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String string(Object value) { return value instanceof String text ? text : "PENDING"; }
    private static String nullableString(Object value) { return value instanceof String text ? text : null; }

    private static Instant parseInstant(Object value) {
        if (!(value instanceof String text)) return Instant.now();
        try { return Instant.parse(text); } catch (Exception ignored) { return Instant.now(); }
    }

    private List<String> stringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    private List<Map<String, Object>> mapList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }
}
