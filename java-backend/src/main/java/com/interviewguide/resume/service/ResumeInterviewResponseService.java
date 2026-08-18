package com.interviewguide.resume.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.interview.domain.InterviewSessionEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Builds resume-facing interview response data without coupling ResumeWorkflowService to JSON parsing. */
@Service
public class ResumeInterviewResponseService {
    /** Decodes the optional final evaluation JSON stored on a completed session. */
    private final ObjectMapper objectMapper;

    /** Injects the application JSON codec used for historical session data. */
    public ResumeInterviewResponseService(ObjectMapper objectMapper) {
        // Store the configured codec so date and map handling match the rest of the application.
        this.objectMapper = objectMapper;
    }

    /** Converts one interview session into the structure returned inside a resume detail response. */
    public InterviewResponse interview(InterviewSessionEntity session) {
        // Parse optional evaluation data while preserving all persisted session metadata.
        return new InterviewResponse(session.getId(), session.getUserId(), session.getCandidateId(), session.getResumeId(),
                session.getJdId(), session.getInterviewDirection(), session.getDifficulty(), session.getTotalQuestions(),
                session.getStatus().name(), session.getAgentStateVersion(), session.getCurrentQuestion(),
                session.getCurrentStage(), session.getIssuedQuestionCount(), session.getPrimaryQuestionCount(),
                session.getTotalPrimaryQuestionCount(), session.getFollowupCount(), parseEvaluation(session.getFinalEvaluationJson()),
                session.getCreatedAt(), session.getUpdatedAt());
    }

    /** Parses a stored evaluation and returns an empty object for active or malformed sessions. */
    private Map<String, Object> parseEvaluation(String raw) {
        // Active sessions have no final evaluation yet.
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        // Historical malformed JSON must not make the resume detail endpoint fail.
        try {
            return objectMapper.readValue(raw, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
