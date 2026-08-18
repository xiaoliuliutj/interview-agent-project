package com.interviewguide.resume.service;

import com.interviewguide.common.exception.BusinessException;

import com.interviewguide.resume.domain.ResumeAnalysisResponse;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateMapper;
import com.interviewguide.resume.mapper.ResumeMapper;
import com.interviewguide.common.redis.JavaTaskStatusCache;
import com.interviewguide.resume.service.ResumeAnalysisResponseService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResumeAnalysisService {
    private final ResumeMapper resumeMapper;
    private final CandidateMapper candidateMapper;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisWorkerService worker;
    private final JavaTaskStatusCache taskCache;
    private final ResumeAnalysisResponseService responseUtil;

    public ResumeAnalysisService(ResumeMapper resumeMapper,
                                 CandidateMapper candidateMapper,
                                 ResumeAnalysisPersistenceService persistence,
                                 ResumeAnalysisWorkerService worker,
                                 JavaTaskStatusCache taskCache,
                                 ResumeAnalysisResponseService responseUtil) {
        this.resumeMapper = resumeMapper;
        this.candidateMapper = candidateMapper;
        this.persistence = persistence;
        this.worker = worker;
        this.taskCache = taskCache;
        this.responseUtil = responseUtil;
    }

    public ResumeAnalysisResponse submit(String resumeId, String userId, String targetRole) {
        if (targetRole == null || targetRole.isBlank()) {
            throw new BusinessException("TARGET_ROLE_REQUIRED", "targetRole is required for resume analysis");
        }
        ResumeEntity resume = resumeMapper.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "resume not found"));
        CandidateEntity candidate = candidateMapper.findById(resume.getCandidateId())
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
            return responseUtil.fromEntity(analysis);
        } catch (RuntimeException error) {
            persistence.fail(analysis.getId(), responseUtil.failureMessage(error));
            throw error;
        }
    }

    public ResumeAnalysisResponse latest(String resumeId) {
        // Polling clients normally observe Redis first. PostgreSQL is used only
        // after expiry, a Redis fault, or for historical records not cached yet.
        var cached = taskCache.latestResumeAnalysis(resumeId);
        if (cached.isPresent()) return responseUtil.fromCache(cached.get());
        ResumeAnalysisEntity analysis = persistence.latest(resumeId);
        return analysis == null ? null : responseUtil.fromEntity(analysis);
    }

    public List<ResumeAnalysisResponse> list(String resumeId) {
        return persistence.list(resumeId).stream().map(responseUtil::fromEntity).toList();
    }

    public void deleteByResumeId(String resumeId) { persistence.deleteByResumeId(resumeId); }

    public void cancelActiveForResumeIds(List<String> resumeIds) {
        persistence.cancelActiveForResumeIds(resumeIds);
    }

}
