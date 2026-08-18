package com.interviewguide.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.interview.domain.InterviewSessionEntity;
import com.interviewguide.interview.domain.InterviewTurnEntity;
import com.interviewguide.interview.domain.InterviewTurnResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Converts persisted interview entities into response data without placing serialization logic in services.
 */
@Service
public class InterviewResponseService {
    /** Stores the shared JSON codec used to parse a completed interview's final evaluation. */
    private final ObjectMapper objectMapper;

    /** Creates the response utility with Spring's configured JSON codec. */
    public InterviewResponseService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** Converts one persisted session to the response used by list, start and status endpoints. */
    public InterviewResponse fromSession(InterviewSessionEntity session) {
        // The final evaluation is optional until the lower Agent has completed the session.
        Map<String, Object> finalEvaluation = finalEvaluation(session.getFinalEvaluationJson());
        return new InterviewResponse(session.getId(), session.getUserId(), session.getCandidateId(), session.getResumeId(),
                session.getJdId(), session.getInterviewDirection(), session.getDifficulty(), session.getTotalQuestions(),
                session.getStatus().name(), session.getAgentStateVersion(), session.getCurrentQuestion(),
                session.getCurrentStage(), session.getIssuedQuestionCount(), session.getPrimaryQuestionCount(),
                session.getTotalPrimaryQuestionCount(), session.getFollowupCount(), finalEvaluation,
                session.getCreatedAt(), session.getUpdatedAt());
    }

    /** Converts persisted turns to candidate-visible records while assigning their stable response indexes. */
    public List<InterviewTurnResponse> fromTurns(List<InterviewTurnEntity> turns) {
        // Index generation belongs to response conversion, not to database or business state mutation.
        return IntStream.range(0, turns.size()).mapToObj(index -> {
            InterviewTurnEntity turn = turns.get(index);
            return new InterviewTurnResponse(index, turn.getStage(), turn.getQuestion(), turn.getCandidateAnswer(),
                    turn.getEvaluationSummary(), turn.getScore(), turn.getCreatedAt());
        }).toList();
    }

    /** Parses a persisted JSON final evaluation and hides malformed historical data behind an empty object. */
    private Map<String, Object> finalEvaluation(String raw) {
        // An empty value is normal for active interviews.
        if (raw == null || raw.isBlank()) return Map.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception ignored) { return Map.of(); }
    }
}
