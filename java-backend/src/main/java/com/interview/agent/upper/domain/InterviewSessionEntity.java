package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "interview_sessions")
public class InterviewSessionEntity {
    @Id
    private String id;
    private String userId;
    private String candidateId;
    private String resumeId;
    private String jdId;
    private int totalQuestions;
    @Enumerated(EnumType.STRING)
    private InterviewSessionStatus status;
    @Version
    private long stateVersion;
    @Column(columnDefinition = "TEXT")
    private String currentQuestion;
    @Column(columnDefinition = "TEXT")
    private String draftAnswer;
    private Instant createdAt;
    private Instant updatedAt;

    protected InterviewSessionEntity() {
    }

    public InterviewSessionEntity(
            String id,
            String userId,
            String candidateId,
            String resumeId,
            String jdId) {
        this.id = id;
        this.userId = userId;
        this.candidateId = candidateId;
        this.resumeId = resumeId;
        this.jdId = jdId;
        this.totalQuestions = 6;
        this.status = InterviewSessionStatus.INITIALIZING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public InterviewSessionEntity(
            String id,
            String userId,
            String candidateId,
            String resumeId,
            String jdId,
            int totalQuestions) {
        this(id, userId, candidateId, resumeId, jdId);
        this.totalQuestions = Math.max(1, totalQuestions);
    }

    public void activate(String question) {
        this.status = InterviewSessionStatus.ACTIVE;
        this.currentQuestion = question;
        this.updatedAt = Instant.now();
    }

    public void applyAgentResponse(String answer, String lowerStatus) {
        this.currentQuestion = answer;
        this.draftAnswer = null;
        this.status = switch (lowerStatus) {
            case "COMPLETED" -> InterviewSessionStatus.COMPLETED;
            case "PAUSED" -> InterviewSessionStatus.PAUSED;
            case "FAILED" -> InterviewSessionStatus.FAILED;
            default -> InterviewSessionStatus.ACTIVE;
        };
        this.updatedAt = Instant.now();
    }

    public void saveDraft(String answer) {
        this.draftAnswer = answer == null ? "" : answer;
        this.updatedAt = Instant.now();
    }

    public void complete() {
        this.status = InterviewSessionStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getCandidateId() { return candidateId; }
    public String getResumeId() { return resumeId; }
    public String getJdId() { return jdId; }
    public int getTotalQuestions() { return totalQuestions; }
    public InterviewSessionStatus getStatus() { return status; }
    public long getStateVersion() { return stateVersion; }
    public String getCurrentQuestion() { return currentQuestion; }
    public String getDraftAnswer() { return draftAnswer; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
