package com.interviewguide.resume.mapper;

import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

/** MyBatis DAO for asynchronous resume-analysis task state and results. */
@Mapper
public interface ResumeAnalysisMapper {
    /** Inserts a newly created analysis task. */
    int insert(ResumeAnalysisEntity entity);
    /** Updates one existing analysis task. */
    int update(ResumeAnalysisEntity entity);
    /** Persists a task by choosing insert for new rows and update for existing rows. */
    default ResumeAnalysisEntity save(ResumeAnalysisEntity entity) {
        if (entity.getId() == null) insert(entity); else update(entity);
        return entity;
    }
    /** Finds an analysis task by its generated numeric identifier. */
    Optional<ResumeAnalysisEntity> findById(Long id);
    /** Lists all analysis attempts for one resume in newest-first order. */
    List<ResumeAnalysisEntity> findByResumeIdOrderByCreatedAtDesc(String resumeId);
    /** Finds the most recent analysis attempt for one resume. */
    Optional<ResumeAnalysisEntity> findFirstByResumeIdOrderByCreatedAtDesc(String resumeId);
    /** Deletes all analysis tasks belonging to one deleted resume. */
    void deleteByResumeId(String resumeId);
    /** Finds active tasks for a set of resume identifiers and task statuses. */
    List<ResumeAnalysisEntity> findByResumeIdInAndStatusIn(@Param("resumeIds") Collection<String> resumeIds, @Param("statuses") Collection<String> statuses);
}
