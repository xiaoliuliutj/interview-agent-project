package com.interviewguide.resume.mapper;

import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

@Mapper
public interface ResumeAnalysisRepository {
    int insert(ResumeAnalysisEntity entity);
    int update(ResumeAnalysisEntity entity);
    default ResumeAnalysisEntity save(ResumeAnalysisEntity entity) {
        if (entity.getId() == null) insert(entity); else update(entity);
        return entity;
    }
    Optional<ResumeAnalysisEntity> findById(Long id);
    List<ResumeAnalysisEntity> findByResumeIdOrderByCreatedAtDesc(String resumeId);
    Optional<ResumeAnalysisEntity> findFirstByResumeIdOrderByCreatedAtDesc(String resumeId);
    void deleteByResumeId(String resumeId);
    List<ResumeAnalysisEntity> findByResumeIdInAndStatusIn(@Param("resumeIds") Collection<String> resumeIds, @Param("statuses") Collection<String> statuses);
}
