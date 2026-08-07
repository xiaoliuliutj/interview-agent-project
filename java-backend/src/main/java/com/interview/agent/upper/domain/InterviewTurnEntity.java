package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "interview_turns")
public class InterviewTurnEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sessionId;
    private String runId;
    @Column(columnDefinition = "TEXT")
    private String question;
    @Column(columnDefinition = "TEXT")
    private String candidateAnswer;
    @Column(columnDefinition = "TEXT")
    private String evaluationSummary;
    private Integer score;
    @Column(columnDefinition = "TEXT")
    private String answerSummary;
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;
    @Column(columnDefinition = "TEXT")
    private String weaknessesJson;
    @Column(columnDefinition = "TEXT")
    private String preferencesJson;
    private String stage;
    private Instant createdAt;

    protected InterviewTurnEntity() {
    }

    public InterviewTurnEntity(
            String sessionId,
            String runId,
            String question,
            String candidateAnswer,
            String evaluationSummary) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.question = question;
        this.candidateAnswer = candidateAnswer;
        this.evaluationSummary = evaluationSummary;
        this.score = 0;
        this.answerSummary = evaluationSummary;
        this.createdAt = Instant.now();
    }

    public InterviewTurnEntity(String sessionId, String runId, String question,
            String candidateAnswer, String evaluationSummary, Integer score,
            String answerSummary, String strengthsJson, String weaknessesJson) {
        this(sessionId, runId, question, candidateAnswer, evaluationSummary);
        this.score = score;
        this.answerSummary = answerSummary;
        this.strengthsJson = strengthsJson;
        this.weaknessesJson = weaknessesJson;
    }

    public InterviewTurnEntity(String sessionId, String runId, String question,
            String candidateAnswer, String evaluationSummary, Integer score,
            String answerSummary, String strengthsJson, String weaknessesJson,
            String preferencesJson) {
        this(sessionId, runId, question, candidateAnswer, evaluationSummary, score,
                answerSummary, strengthsJson, weaknessesJson);
        this.preferencesJson = preferencesJson;
    }

    public void setStage(String stage) { this.stage = stage; }
    public String getStage() { return stage; }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getRunId() { return runId; }
    public String getQuestion() { return question; }
    public String getCandidateAnswer() { return candidateAnswer; }
    public String getEvaluationSummary() { return evaluationSummary; }
    public Integer getScore() { return score; }
    public String getAnswerSummary() { return answerSummary; }
    public String getStrengthsJson() { return strengthsJson; }
    public String getWeaknessesJson() { return weaknessesJson; }
    public String getPreferencesJson() { return preferencesJson; }
    public Instant getCreatedAt() { return createdAt; }
}
