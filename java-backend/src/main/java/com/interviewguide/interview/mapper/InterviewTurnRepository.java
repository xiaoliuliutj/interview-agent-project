package com.interviewguide.interview.mapper;

import com.interviewguide.interview.domain.InterviewTurnEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface InterviewTurnRepository {
    int upsert(InterviewTurnEntity entity);
    default InterviewTurnEntity save(InterviewTurnEntity entity) { upsert(entity); return entity; }
    Optional<InterviewTurnEntity> findByRunId(String runId);
    List<InterviewTurnEntity> findBySessionIdOrderByCreatedAt(String sessionId);
    void deleteBySessionId(String sessionId);
    void deleteAll(List<InterviewTurnEntity> entities);
}
