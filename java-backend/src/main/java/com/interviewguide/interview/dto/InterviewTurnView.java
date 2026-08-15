package com.interviewguide.interview.dto;

import java.time.Instant;

/** Candidate-visible turn only. Scores, memory and retrieval evidence stay in the lower layer. */
public record InterviewTurnView(int index, String stage, String question, String answer,
                                String evaluationSummary, Integer score, Instant answeredAt) {
}
