package com.interviewguide.interview.domain;


import java.time.Instant;

public class InterviewSessionEntity {
    private String id;
    private String userId;
    private String candidateId;
    private String resumeId;
    private String jdId;
    private String interviewDirection;
    private String difficulty;
    private int totalQuestions;
    private int issuedQuestionCount;
    private int primaryQuestionCount;
    private int totalPrimaryQuestionCount;
    private int followupCount;
    private InterviewSessionStatus status;
    private long stateVersion;
    /** Python Agent 浼氳瘽鐗堟湰锛涗笌 JPA 琛岀増鏈垎绂伙紝鐢ㄤ簬璺ㄥ眰涓€鑷存€ф牎楠屻€?*/
    private long agentStateVersion;
    private String currentQuestion;
    private String currentStage;
    private String finalEvaluationJson;
    private Instant createdAt;
    private Instant updatedAt;

    public InterviewSessionEntity() {
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

    public void configure(String interviewDirection, String difficulty) {
        this.interviewDirection = interviewDirection;
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

    public void applyCounters(Integer issued, Integer primary, Integer totalPrimary, Integer followups) {
        if (issued != null) this.issuedQuestionCount = issued;
        if (primary != null) this.primaryQuestionCount = primary;
        if (totalPrimary != null) this.totalPrimaryQuestionCount = totalPrimary;
        if (followups != null) this.followupCount = followups;
    }

    public void setFinalEvaluationJson(String value) { this.finalEvaluationJson = value; }

    /** MyBatis has no automatic version interceptor; service code advances this durable version explicitly. */
    public void advanceStateVersion() { this.stateVersion++; }

    public void complete() {
        this.status = InterviewSessionStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getCandidateId() { return candidateId; }
    public String getResumeId() { return resumeId; }
    public String getJdId() { return jdId; }
    public String getInterviewDirection() { return interviewDirection; }
    public String getDifficulty() { return difficulty; }
    public int getTotalQuestions() { return totalQuestions; }
    public int getIssuedQuestionCount() { return issuedQuestionCount; }
    public int getPrimaryQuestionCount() { return primaryQuestionCount; }
    public int getTotalPrimaryQuestionCount() { return totalPrimaryQuestionCount; }
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
