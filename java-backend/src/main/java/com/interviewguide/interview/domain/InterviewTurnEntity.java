package com.interviewguide.interview.domain;


import java.time.Instant;

/** Persistent entity representing one generated question and submitted answer. */
public class InterviewTurnEntity {
    private Long id;
    private String sessionId;
    private String runId;
    private String question;
    private String candidateAnswer;
    private String stage;
    private Instant createdAt;
    private String evaluationSummary;
    private Integer score;
    private String strengthsJson;
    private String weaknessesJson;

    public InterviewTurnEntity() {
    }

    public InterviewTurnEntity(
            String sessionId,
            String runId,
            String question,
            String candidateAnswer) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.question = question;
        this.candidateAnswer = candidateAnswer;
        this.createdAt = Instant.now();
    }

    public void setStage(String stage) { this.stage = stage; }
    public void setEvaluationSummary(String value) { this.evaluationSummary = value; }
    public void setScore(Integer value) { this.score = value; }
    public void setStrengthsJson(String value) { this.strengthsJson = value; }
    public void setWeaknessesJson(String value) { this.weaknessesJson = value; }
    public String getStage() { return stage; }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getRunId() { return runId; }
    public String getQuestion() { return question; }
    public String getCandidateAnswer() { return candidateAnswer; }
    public Instant getCreatedAt() { return createdAt; }
    public String getEvaluationSummary() { return evaluationSummary; }
    public Integer getScore() { return score; }
    public String getStrengthsJson() { return strengthsJson; }
    public String getWeaknessesJson() { return weaknessesJson; }
}
