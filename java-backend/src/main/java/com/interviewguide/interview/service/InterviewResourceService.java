package com.interviewguide.interview.service;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.interview.domain.InterviewSessionEntity;
import com.interviewguide.interview.mapper.InterviewSessionMapper;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateMapper;
import com.interviewguide.resume.mapper.ResumeMapper;
import org.springframework.stereotype.Service;

/**
 * Centralizes ownership checks shared by interview endpoint flows.
 */
@Service
public class InterviewResourceService {
    /** Stores the resume DAO used to load a requested resume. */
    private final ResumeMapper resumeMapper;
    /** Stores the candidate DAO used to verify the resume owner. */
    private final CandidateMapper candidateMapper;
    /** Stores the session mapper used to load the current interview state. */
    private final InterviewSessionMapper sessionMapper;

    /** Creates the ownership utility from the mappers used by interview flows. */
    public InterviewResourceService(ResumeMapper resumeMapper, CandidateMapper candidateMapper,
                               InterviewSessionMapper sessionMapper) {
        this.resumeMapper = resumeMapper;
        this.candidateMapper = candidateMapper;
        this.sessionMapper = sessionMapper;
    }

    /** Loads a resume and verifies that its candidate belongs to the caller. */
    public ResumeEntity ownedResume(String resumeId, String userId) {
        ResumeEntity resume = resumeMapper.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "resume not found"));
        CandidateEntity candidate = candidateForResume(resume);
        if (!userId.equals(candidate.getUserId())) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "resume does not belong to current user");
        }
        return resume;
    }

    /** Loads the candidate that owns an already validated resume. */
    public CandidateEntity candidateForResume(ResumeEntity resume) {
        // The current-resume check in the start flow needs the same candidate row.
        return candidateMapper.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
    }

    /** Loads a session and verifies that it belongs to the caller. */
    public InterviewSessionEntity ownedSession(String sessionId, String userId) {
        InterviewSessionEntity session = sessionMapper.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        return session;
    }
}
