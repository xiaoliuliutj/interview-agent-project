package com.interviewguide.resume.service;

import com.interviewguide.common.exception.BusinessException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import com.interviewguide.resume.mapper.ResumeAnalysisRepository;
import com.interviewguide.infrastructure.redis.JavaTaskStatusCache;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Collection;

@Service
public class ResumeAnalysisPersistenceService {
    private final ResumeAnalysisRepository repository;
    private final ObjectMapper objectMapper;
    private final JavaTaskStatusCache taskCache;

    public ResumeAnalysisPersistenceService(
            ResumeAnalysisRepository repository, ObjectMapper objectMapper, JavaTaskStatusCache taskCache) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.taskCache = taskCache;
    }

    @Transactional
    public ResumeAnalysisEntity create(String resumeId, String targetRole) {
        ResumeAnalysisEntity entity = repository.save(new ResumeAnalysisEntity(resumeId, targetRole));
        cacheAfterCommit(entity);
        return entity;
    }

    @Transactional
    public void complete(Long id, AgentResponse response) {
        Map<String, Object> output = response.output();
        if (output == null) {
            throw new BusinessException("RESUME_ANALYSIS_OUTPUT_MISSING", "resume analysis output is missing");
        }
        ResumeAnalysisEntity entity = required(id);
        entity.complete(
                integer(output, "overallScore"), integer(output, "contentScore"),
                integer(output, "structureScore"), integer(output, "skillMatchScore"),
                integer(output, "expressionScore"), integer(output, "projectScore"),
                string(output, "summary"), json(output.get("strengths")),
                json(output.get("suggestions")), json(output.get("issues")));
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    @Transactional
    public void fail(Long id, String message) { ResumeAnalysisEntity entity = required(id); entity.fail(message); repository.save(entity); cacheAfterCommit(entity); }

    public ResumeAnalysisEntity latest(String resumeId) {
        return repository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId).orElse(null);
    }

    public List<ResumeAnalysisEntity> list(String resumeId) {
        return repository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    @Transactional
    public void deleteByResumeId(String resumeId) {
        repository.deleteByResumeId(resumeId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskCache.removeLatestResumeAnalysis(resumeId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { taskCache.removeLatestResumeAnalysis(resumeId); }
        });
    }

    @Transactional
    public void cancelActiveForResumeIds(Collection<String> resumeIds) {
        if (resumeIds.isEmpty()) return;
        repository.findByResumeIdInAndStatusIn(resumeIds, List.of("PENDING", "PROCESSING"))
                .forEach(entity -> { entity.cancel(); repository.save(entity); cacheAfterCommit(entity); });
    }

    @Transactional
    public ResumeAnalysisEntity beginAttempt(Long id) {
        ResumeAnalysisEntity analysis = required(id);
        if (!analysis.canBeginAttempt()) return null;
        analysis.beginAttempt();
        repository.save(analysis);
        cacheAfterCommit(analysis);
        return analysis;
    }

    @Transactional
    public void recordRetryableFailure(Long id, String message) {
        ResumeAnalysisEntity entity = required(id);
        entity.recordRetryableFailure(message);
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    @Transactional
    public void cancel(Long id) { ResumeAnalysisEntity entity = required(id); entity.cancel(); repository.save(entity); cacheAfterCommit(entity); }

    public boolean isCancelled(Long id) { return required(id).isCancelled(); }

    private ResumeAnalysisEntity required(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found"));
    }

    private void cacheAfterCommit(ResumeAnalysisEntity entity) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskCache.updateResumeAnalysis(entity);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                taskCache.updateResumeAnalysis(entity);
            }
        });
    }

    private int integer(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "閻犲洤瀚悳顖滅磼閹惧浜紓鍌氭惈閻?" + key);
    }

    private String string(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "閻犲洤瀚悳顖滅磼閹惧浜紓鍌氭惈閻?" + key);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException error) { throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "resume analysis output JSON is invalid"); }
    }
}
