package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "interview_tasks")
public class InterviewTaskEntity {
    @Id
    private String id;
    private String taskType;
    @Enumerated(EnumType.STRING)
    private InterviewTaskStatus status;
    private String sessionId;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    protected InterviewTaskEntity() {
    }

    public InterviewTaskEntity(String id, String taskType) {
        this.id = id;
        this.taskType = taskType;
        this.status = InterviewTaskStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void running() { status = InterviewTaskStatus.RUNNING; updatedAt = Instant.now(); }
    public void complete(String sessionId) { status = InterviewTaskStatus.COMPLETED; this.sessionId = sessionId; updatedAt = Instant.now(); }
    public void fail(String errorMessage) { status = InterviewTaskStatus.FAILED; this.errorMessage = errorMessage; updatedAt = Instant.now(); }
    public String getId() { return id; }
    public InterviewTaskStatus getStatus() { return status; }
    public String getSessionId() { return sessionId; }
    public String getErrorMessage() { return errorMessage; }
}
