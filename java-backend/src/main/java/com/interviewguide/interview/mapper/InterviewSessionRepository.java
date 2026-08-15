package com.interviewguide.interview.mapper;

import com.interviewguide.interview.domain.InterviewSessionEntity;
import com.interviewguide.interview.domain.InterviewSessionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;
import java.util.List;

@Mapper
public interface InterviewSessionRepository {
    int upsert(InterviewSessionEntity entity);
    default InterviewSessionEntity save(InterviewSessionEntity entity) { upsert(entity); return entity; }
    Optional<InterviewSessionEntity> findById(String id);
    void delete(InterviewSessionEntity entity);
    List<InterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<InterviewSessionEntity> findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
            @Param("userId") String userId, @Param("resumeId") String resumeId,
            @Param("statuses") List<InterviewSessionStatus> statuses);
    Optional<InterviewSessionEntity> findByIdForUpdate(String id);
}
