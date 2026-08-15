package com.interviewguide.interview.dto;

import java.util.List;

public record InterviewDetailView(InterviewView session, List<InterviewTurnView> turns) {
}
