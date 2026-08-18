package com.interviewguide.resume.service;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateMapper;
import com.interviewguide.resume.mapper.ResumeMapper;
import com.interviewguide.common.security.UserIdentityResolver;
import org.springframework.stereotype.Service;

/** Centralises resume ownership decisions for every resume endpoint. */
@Service
public class ResumeResourceService {
    /** Loads resume records for access checks. */
    private final ResumeMapper resumeMapper;
    /** Loads candidate records which own resumes. */
    private final CandidateMapper candidateMapper;
    /** Resolves and validates the caller identity. */
    private final UserIdentityResolver identity;

    /** Creates the ownership utility from the two mappers and identity resolver. */
    public ResumeResourceService(ResumeMapper resumeMapper, CandidateMapper candidateMapper,
                            UserIdentityResolver identity) {
        // Store the resume mapper used to locate the requested row.
        this.resumeMapper = resumeMapper;
        // Store the candidate mapper used to verify ownership.
        this.candidateMapper = candidateMapper;
        // Store the identity resolver used to reject missing headers.
        this.identity = identity;
    }

    /** Returns an owned resume or raises the stable module error. */
    public ResumeEntity owned(String id, String userId) {
        // Resolve the resume id before checking its candidate owner.
        ResumeEntity resume = resumeMapper.findById(id)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "resume not found"));
        // Reject a resume whose candidate is not associated with the caller.
        if (!owns(resume, identity.require(userId))) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "resume does not belong to current user");
        }
        // Return the authorised domain entity to the service.
        return resume;
    }

    /** Tests ownership without throwing, for list filtering. */
    public boolean owns(ResumeEntity resume, String userId) {
        // Look up the candidate and compare its stored user id with the validated owner.
        return candidateMapper.findById(resume.getCandidateId())
                .map(candidate -> userId.equals(candidate.getUserId())).orElse(false);
    }

    /** Returns the candidate which owns a validated resume. */
    public CandidateEntity candidate(ResumeEntity resume) {
        // Missing candidate data indicates a broken relationship rather than an empty result.
        return candidateMapper.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
    }
}
