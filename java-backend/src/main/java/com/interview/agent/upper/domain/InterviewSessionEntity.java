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
    private String skillId;
    private String difficulty;
    private int totalQuestions;
    @Enumerated(EnumType.STRING)
    private InterviewSessionStatus status;
    @Version
    private long stateVersion;
    @Column(columnDefinition = "TEXT")
    private String currentQuestion;
    @Column(columnDefinition = "TEXT")
    private String draftAnswer;
    private Integer overallScore;
    @Column(columnDefinition = "TEXT")
    private String finalSummary;
    private String evaluateStatus;
    @Column(columnDefinition = "TEXT")
    private String evaluateError;
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

    public void configure(String skillId, String difficulty) {
        this.skillId = skillId;
        this.difficulty = difficulty;
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

    public void applyFinalEvaluation(Integer score, String summary) {
        this.overallScore = score;
        this.finalSummary = summary;
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
    public String getSkillId() { return skillId; }
    public String getDifficulty() { return difficulty; }
    public int getTotalQuestions() { return totalQuestions; }
    public InterviewSessionStatus getStatus() { return status; }
    public long getStateVersion() { return stateVersion; }
    public String getCurrentQuestion() { return currentQuestion; }
    public String getDraftAnswer() { return draftAnswer; }
    public Integer getOverallScore() { return overallScore; }
    public String getFinalSummary() { return finalSummary; }
    public String getEvaluateStatus() { return evaluateStatus; }
    public String getEvaluateError() { return evaluateError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
