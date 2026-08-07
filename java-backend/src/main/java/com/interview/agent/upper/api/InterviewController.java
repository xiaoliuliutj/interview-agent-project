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
import org.springframework.web.bind.annotation.RequestHeader;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.UserIdentityResolver;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService interviewService;
    private final InterviewTaskService taskService;
    private final UserIdentityResolver identity;

    public InterviewController(InterviewService interviewService, InterviewTaskService taskService,
            UserIdentityResolver identity) {
        this.interviewService = interviewService;
        this.taskService = taskService;
        this.identity = identity;
    }

    @PostMapping
    public InterviewView start(@Valid @RequestBody CreateInterviewRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireBodyOwner(request.userId(), userId);
        return interviewService.start(request);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InterviewTaskEntity startAsync(@Valid @RequestBody CreateInterviewRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireBodyOwner(request.userId(), userId);
        return taskService.submitCreate(request);
    }

    @PostMapping("/{sessionId}/answers")
    public InterviewView submitAnswer(
            @PathVariable String sessionId,
            @Valid @RequestBody SubmitInterviewAnswerRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireBodyOwner(request.userId(), userId);
        return interviewService.submitAnswer(
                sessionId, request.userId(), request.answer(), request.runId());
    }

    private void requireBodyOwner(String bodyUserId, String headerUserId) {
        if (!identity.require(headerUserId).equals(bodyUserId)) {
            throw new BusinessException("USER_ID_MISMATCH", "request userId does not match X-User-Id");
        }
    }
}
