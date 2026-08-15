package com.interviewguide.resume.mapper;

import com.interviewguide.resume.domain.ResumeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Optional;
import java.util.List;

@Mapper
public interface ResumeRepository {
    int upsert(ResumeEntity entity);
    default ResumeEntity save(ResumeEntity entity) { upsert(entity); return entity; }
    Optional<ResumeEntity> findById(String id);
    List<ResumeEntity> findAll();
    void delete(ResumeEntity entity);
    Optional<ResumeEntity> findFirstByCandidateIdAndFileHash(@Param("candidateId") String candidateId, @Param("fileHash") String fileHash);
    List<ResumeEntity> findByCandidateId(String candidateId);
    Optional<ResumeEntity> findFirstByCandidateIdOrderByVersionDesc(String candidateId);
}
