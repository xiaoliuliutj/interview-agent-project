package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.CreateInterviewRequest;

public record InterviewTaskMessage(String taskId, CreateInterviewRequest request) { }
