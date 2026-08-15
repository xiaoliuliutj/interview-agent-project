package com.interviewguide.resume.mapper;

import com.interviewguide.resume.domain.CandidateEntity;
import org.apache.ibatis.annotations.Mapper;
import java.util.Optional;

@Mapper
public interface CandidateRepository {
    int upsert(CandidateEntity entity);
    default CandidateEntity save(CandidateEntity entity) { upsert(entity); return entity; }
    Optional<CandidateEntity> findById(String id);
    Optional<CandidateEntity> findByUserId(String userId);
}
