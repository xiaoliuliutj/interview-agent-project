package com.interview.agent.upper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.upper.api.dto.ResumeAnalysisView;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.ResumeAnalysisEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ResumeAnalysisService {
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisWorker worker;
    private final ObjectMapper objectMapper;

    public ResumeAnalysisService(ResumeRepository resumeRepository,
                                 CandidateRepository candidateRepository,
                                 ResumeAnalysisPersistenceService persistence,
                                 ResumeAnalysisWorker worker,
                                 ObjectMapper objectMapper) {
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.persistence = persistence;
        this.worker = worker;
        this.objectMapper = objectMapper;
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
