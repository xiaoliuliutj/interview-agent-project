package com.interviewguide.interview.mapper;

import com.interviewguide.interview.domain.InterviewTurnEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** MyBatis DAO for interview question-and-answer turns. */
@Mapper
public interface InterviewTurnMapper {
    /** Inserts one completed interview turn. */
    int upsert(InterviewTurnEntity entity);
    /** Persists one turn and returns the supplied entity. */
    default InterviewTurnEntity save(InterviewTurnEntity entity) { upsert(entity); return entity; }
    /** Lists a session's turns in creation order. */
    List<InterviewTurnEntity> findBySessionIdOrderByCreatedAt(String sessionId);
    /** Deletes turns by their session business identifier. */
    void deleteBySessionId(String sessionId);
    /** Deletes a supplied collection of turn entities. */
    void deleteAll(List<InterviewTurnEntity> entities);
}
