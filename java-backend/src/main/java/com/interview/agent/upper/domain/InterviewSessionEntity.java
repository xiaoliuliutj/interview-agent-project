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
    private int issuedQuestionCount;
    private int primaryQuestionCount;
    private int followupCount;
    @Enumerated(EnumType.STRING)
    private InterviewSessionStatus status;
    @Version
    private long stateVersion;
    /** Python Agent 会话版本；与 JPA 行版本分离，用于跨层一致性校验。 */
    private long agentStateVersion;
    @Column(columnDefinition = "TEXT")
    private String currentQuestion;
    @Column(name = "current_stage", length = 32)
    private String currentStage;
    @Column(columnDefinition = "TEXT")
    private String finalEvaluationJson;
    private Instant createdAt;
    private Instant updatedAt;

    protected InterviewSessionEntity() {
    }

    public InterviewSessionEntity(
            String id,
            String userId,
            String candidateId,
            String resumeId,
            String jdId,
            int totalQuestions) {
        this.id = id;
        this.userId = userId;
        this.candidateId = candidateId;
        this.resumeId = resumeId;
        this.jdId = jdId;
        this.totalQuestions = totalQuestions;
        this.issuedQuestionCount = 0;
        this.status = InterviewSessionStatus.INITIALIZING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void configure(String skillId, String difficulty) {
        this.skillId = skillId;
        this.difficulty = difficulty;
    }

    public void activate(String question) {
        this.status = InterviewSessionStatus.ACTIVE;
        this.currentQuestion = question;
        this.updatedAt = Instant.now();
    }

    public void applyAgentResponse(String answer, String lowerStatus) {
        applyAgentResponse(answer, lowerStatus, agentStateVersion);
    }

    public void applyAgentResponse(String answer, String lowerStatus, long lowerStateVersion) {
        applyAgentResponse(answer, lowerStatus, lowerStateVersion, currentStage);
    }

    public void applyAgentResponse(String answer, String lowerStatus, long lowerStateVersion, String lowerCurrentStage) {
        if (lowerStateVersion < agentStateVersion) {
            throw new IllegalArgumentException("lower Agent state version moved backwards");
        }
        this.currentQuestion = answer;
        this.status = switch (lowerStatus) {
            case "COMPLETED" -> InterviewSessionStatus.COMPLETED;
            case "PAUSED" -> InterviewSessionStatus.PAUSED;
            case "FAILED" -> InterviewSessionStatus.FAILED;
            default -> InterviewSessionStatus.ACTIVE;
        };
        this.agentStateVersion = lowerStateVersion;
        if (lowerCurrentStage != null && !lowerCurrentStage.isBlank()) {
            this.currentStage = lowerCurrentStage;
        }
        this.updatedAt = Instant.now();
    }

    public void applyCounters(Integer issued, Integer primary, Integer followups) {
        if (issued != null) this.issuedQuestionCount = issued;
        if (primary != null) this.primaryQuestionCount = primary;
        if (followups != null) this.followupCount = followups;
    }

    public void setFinalEvaluationJson(String value) { this.finalEvaluationJson = value; }

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
    public int getIssuedQuestionCount() { return issuedQuestionCount; }
    public int getPrimaryQuestionCount() { return primaryQuestionCount; }
    public int getFollowupCount() { return followupCount; }
    public String getFinalEvaluationJson() { return finalEvaluationJson; }
    public InterviewSessionStatus getStatus() { return status; }
    public long getStateVersion() { return stateVersion; }
    public long getAgentStateVersion() { return agentStateVersion; }
    public String getCurrentQuestion() { return currentQuestion; }
    public String getCurrentStage() { return currentStage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
