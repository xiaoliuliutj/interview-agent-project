package com.interviewguide.resume.mapper;

import com.interviewguide.resume.domain.CandidateEntity;
import org.apache.ibatis.annotations.Mapper;
import java.util.Optional;

/** MyBatis DAO for candidates and their current-resume reference. */
@Mapper
public interface CandidateMapper {
    /** Inserts or updates one candidate entity. */
    int upsert(CandidateEntity entity);
    /** Persists one candidate and returns the supplied entity. */
    default CandidateEntity save(CandidateEntity entity) { upsert(entity); return entity; }
    /** Finds a candidate by its business identifier. */
    Optional<CandidateEntity> findById(String id);
    /** Finds the candidate associated with one application user. */
    Optional<CandidateEntity> findByUserId(String userId);
}
