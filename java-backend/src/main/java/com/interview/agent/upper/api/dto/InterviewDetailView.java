package com.interview.agent.upper.api.dto;

import java.util.List;

public record InterviewDetailView(InterviewView session, List<InterviewTurnView> turns) {
}
