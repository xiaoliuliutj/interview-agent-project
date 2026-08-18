package com.interviewguide.interview.domain;

import java.util.List;

/** Complete interview read model containing the session summary and its ordered turns. */
public record InterviewDetailResponse(InterviewResponse session, List<InterviewTurnResponse> turns) {
}
