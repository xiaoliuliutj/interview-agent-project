package com.interview.agent.upper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.upper.api.dto.ResumeAnalysisView;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.ResumeAnalysisEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResumeAnalysisService {
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisWorker worker;
    private final ObjectMapper objectMapper;
    private final String defaultTargetRole;

    public ResumeAnalysisService(
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            ResumeAnalysisPersistenceService persistence,
            ResumeAnalysisWorker worker,
            ObjectMapper objectMapper,
            @Value("${agent.default-target-role:Java 后端}") String defaultTargetRole) {
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.persistence = persistence;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.defaultTargetRole = defaultTargetRole;
    }

    public ResumeAnalysisView submit(String resumeId, String userId) {
        ResumeEntity resume = requiredResume(resumeId);
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "候选人不存在"));
        if (!candidate.getUserId().equals(userId)) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "无权分析该简历");
        }
        ResumeAnalysisEntity analysis = persistence.create(resumeId);
        worker.evaluate(analysis.getId(), userId, resume, defaultTargetRole);
        return toView(analysis);
    }

    public ResumeAnalysisView latest(String resumeId) {
        ResumeAnalysisEntity analysis = persistence.latest(resumeId);
        return analysis == null ? null : toView(analysis);
    }

    public List<ResumeAnalysisView> list(String resumeId) {
        return persistence.list(resumeId).stream().map(this::toView).toList();
    }

    public void deleteByResumeId(String resumeId) { persistence.deleteByResumeId(resumeId); }

    private ResumeEntity requiredResume(String resumeId) {
        return resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "简历不存在"));
    }

    private ResumeAnalysisView toView(ResumeAnalysisEntity entity) {
        return new ResumeAnalysisView(
                entity.getId(), entity.getStatus(), entity.getOverallScore(),
                entity.getContentScore(), entity.getStructureScore(),
                entity.getSkillMatchScore(), entity.getExpressionScore(),
                entity.getProjectScore(), entity.getSummary(), entity.getUpdatedAt(),
                stringList(entity.getStrengthsJson()), stringList(entity.getSuggestionsJson()),
                entity.getError());
    }

    private List<String> stringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception error) { return List.of(); }
    }
}
