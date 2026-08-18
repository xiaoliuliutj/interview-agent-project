package com.interviewguide.resume.service;

import com.interviewguide.common.exception.BusinessException;

import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import com.interviewguide.resume.mapper.ResumeAnalysisMapper;
import com.interviewguide.common.redis.JavaTaskStatusCache;
import com.interviewguide.utils.json.AgentOutputUtil;
import com.interviewguide.utils.transaction.TransactionAfterCommitUtil;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Collection;

/** Maintains the complete durable and cached lifecycle of resume-analysis tasks. */
@Service
public class ResumeAnalysisPersistenceService {
    private final ResumeAnalysisMapper repository;
    private final JavaTaskStatusCache taskCache;
    private final AgentOutputUtil outputUtil;

    /** Injects the analysis mapper, Redis status cache and generic JSON formatter. */
    public ResumeAnalysisPersistenceService(
            ResumeAnalysisMapper repository, JavaTaskStatusCache taskCache, AgentOutputUtil outputUtil) {
        this.repository = repository;
        this.taskCache = taskCache;
        this.outputUtil = outputUtil;
    }

    @Transactional
    /** Creates the initial pending analysis task and publishes its post-commit cache snapshot. */
    public ResumeAnalysisEntity create(String resumeId, String targetRole) {
        ResumeAnalysisEntity entity = repository.save(new ResumeAnalysisEntity(resumeId, targetRole));
        TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(entity));
        return entity;
    }

    @Transactional
    /** Applies a successful Python evaluation response to one active analysis task. */
    public void complete(Long id, AgentResponse response) {
        Map<String, Object> output = response.output();
        if (output == null) {
            throw new BusinessException("RESUME_ANALYSIS_OUTPUT_MISSING", "resume analysis output is missing");
        }
        ResumeAnalysisEntity entity = repository.findById(id).orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found"));
        entity.complete(
                outputUtil.integer(output, "overallScore"), outputUtil.integer(output, "contentScore"),
                outputUtil.integer(output, "structureScore"), outputUtil.integer(output, "skillMatchScore"),
                outputUtil.integer(output, "expressionScore"), outputUtil.integer(output, "projectScore"),
                outputUtil.string(output, "summary"), outputUtil.json(output.get("strengths")),
                outputUtil.json(output.get("suggestions")), outputUtil.json(output.get("issues")));
        repository.save(entity);
        TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(entity));
    }

    @Transactional
    /** Marks one analysis task terminally failed with a bounded public message. */
    public void fail(Long id, String message) {
        ResumeAnalysisEntity entity = repository.findById(id).orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found"));
        entity.fail(message);
        repository.save(entity);
        TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(entity));
    }

    /** Returns the latest task for one resume or null when it has never been analyzed. */
    public ResumeAnalysisEntity latest(String resumeId) {
        return repository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId).orElse(null);
    }

    /** Returns the complete newest-first analysis history for one resume. */
    public List<ResumeAnalysisEntity> list(String resumeId) {
        return repository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    @Transactional
    /** Deletes all analysis tasks and their latest Redis snapshot for a removed resume. */
    public void deleteByResumeId(String resumeId) {
        repository.deleteByResumeId(resumeId);
        TransactionAfterCommitUtil.run(
                () -> taskCache.removeLatestResumeAnalysis(resumeId));
    }

    @Transactional
    /** Cancels every queued or running task for the supplied resume versions. */
    public void cancelActiveForResumeIds(Collection<String> resumeIds) {
        if (resumeIds.isEmpty()) return;
        repository.findByResumeIdInAndStatusIn(resumeIds, List.of("PENDING", "PROCESSING"))
                .forEach(entity -> {
                    entity.cancel();
                    repository.save(entity);
                    TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(entity));
                });
    }

    @Transactional
    /** Atomically changes a pending task to processing and returns null when it cannot start. */
    public ResumeAnalysisEntity beginAttempt(Long id) {
        ResumeAnalysisEntity analysis = repository.findById(id).orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found"));
        if (!analysis.canBeginAttempt()) return null;
        analysis.beginAttempt();
        repository.save(analysis);
        TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(analysis));
        return analysis;
    }

    @Transactional
    /** Stores a retryable failure while keeping the task eligible for a later delivery. */
    public void recordRetryableFailure(Long id, String message) {
        ResumeAnalysisEntity entity = repository.findById(id).orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found"));
        entity.recordRetryableFailure(message);
        repository.save(entity);
        TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(entity));
    }

    @Transactional
    /** Cancels one task so late RabbitMQ deliveries cannot apply stale results. */
    public void cancel(Long id) {
        ResumeAnalysisEntity entity = repository.findById(id).orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found"));
        entity.cancel();
        repository.save(entity);
        TransactionAfterCommitUtil.run(() -> taskCache.updateResumeAnalysis(entity));
    }

    /** Returns whether one task has been cancelled before a worker applies its result. */
    public boolean isCancelled(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "resume analysis was not found")).isCancelled();
    }

}
