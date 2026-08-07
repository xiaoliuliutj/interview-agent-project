package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.CreateInterviewRequest;
import com.interview.agent.upper.api.dto.InterviewView;
import com.interview.agent.upper.api.dto.SubmitInterviewAnswerRequest;
import com.interview.agent.upper.service.InterviewService;
import com.interview.agent.upper.service.InterviewTaskService;
import com.interview.agent.upper.domain.InterviewTaskEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService interviewService;
    private final InterviewTaskService taskService;

    public InterviewController(InterviewService interviewService, InterviewTaskService taskService) {
        this.interviewService = interviewService;
        this.taskService = taskService;
    }

    @PostMapping
    public InterviewView start(@Valid @RequestBody CreateInterviewRequest request) {
        return interviewService.start(request);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InterviewTaskEntity startAsync(@Valid @RequestBody CreateInterviewRequest request) {
        return taskService.submitCreate(request);
    }

    @PostMapping("/{sessionId}/answers")
    public InterviewView submitAnswer(
            @PathVariable String sessionId,
            @Valid @RequestBody SubmitInterviewAnswerRequest request) {
        return interviewService.submitAnswer(
                sessionId, request.userId(), request.answer(), request.runId());
    }
}
