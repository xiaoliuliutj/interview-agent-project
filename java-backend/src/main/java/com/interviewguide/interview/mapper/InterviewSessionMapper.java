package com.interviewguide.interview.mapper;

import com.interviewguide.interview.domain.InterviewSessionEntity;
import com.interviewguide.interview.domain.InterviewSessionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;
import java.util.List;

/** MyBatis DAO for durable interview-session rows. */
@Mapper
public interface InterviewSessionMapper {
    /** Inserts or updates one session entity. */
    int upsert(InterviewSessionEntity entity);
    /** Persists one session and returns the supplied entity. */
    default InterviewSessionEntity save(InterviewSessionEntity entity) { upsert(entity); return entity; }
    /** Finds a session by its business identifier. */
    Optional<InterviewSessionEntity> findById(String id);
    /** Deletes the supplied persisted session. */
    void delete(InterviewSessionEntity entity);
    /** Lists one user's sessions in newest-first order. */
    List<InterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    /** Finds a user's newest unfinished session for one resume. */
    Optional<InterviewSessionEntity> findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
            @Param("userId") String userId, @Param("resumeId") String resumeId,
            @Param("statuses") List<InterviewSessionStatus> statuses);
    /** Locks one session row before a state-changing transaction. */
    Optional<InterviewSessionEntity> findByIdForUpdate(String id);
}
