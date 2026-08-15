package com.interviewguide.resume.domain;


import java.time.Instant;

public class ResumeAnalysisEntity {
    private Long id;
    private String resumeId;
    private String targetRole;
    private String status;
    private Integer overallScore;
    private Integer contentScore;
    private Integer structureScore;
    private Integer skillMatchScore;
    private Integer expressionScore;
    private Integer projectScore;
    private String summary;
    private String strengthsJson;
    private String suggestionsJson;
    private String issuesJson;
    private String error;
    private int retryCount;
    private Instant lastAttemptAt;
    private Instant createdAt;
    private Instant updatedAt;

    public ResumeAnalysisEntity() {
    }

    public ResumeAnalysisEntity(String resumeId, String targetRole) {
        this.resumeId = resumeId;
        this.targetRole = targetRole;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void beginAttempt() {
        this.status = "PROCESSING";
        this.retryCount++;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = this.lastAttemptAt;
    }

    public boolean canBeginAttempt() {
        return "PENDING".equals(status) || "PROCESSING".equals(status);
    }

    public void recordRetryableFailure(String message) {
        this.error = truncate(message);
        this.updatedAt = Instant.now();
    }

    public void complete(
            int overallScore, int contentScore, int structureScore, int skillMatchScore,
            int expressionScore, int projectScore, String summary,
            String strengthsJson, String suggestionsJson) {
        complete(overallScore, contentScore, structureScore, skillMatchScore, expressionScore,
                projectScore, summary, strengthsJson, suggestionsJson, "[]");
    }

    public void complete(
            int overallScore, int contentScore, int structureScore, int skillMatchScore,
            int expressionScore, int projectScore, String summary,
            String strengthsJson, String suggestionsJson, String issuesJson) {
        this.status = "COMPLETED";
        this.overallScore = overallScore;
        this.contentScore = contentScore;
        this.structureScore = structureScore;
        this.skillMatchScore = skillMatchScore;
        this.expressionScore = expressionScore;
        this.projectScore = projectScore;
        this.summary = summary;
        this.strengthsJson = strengthsJson;
        this.suggestionsJson = suggestionsJson;
        this.issuesJson = issuesJson;
        this.error = null;
        this.updatedAt = Instant.now();
    }

    public void fail(String error) {
        this.status = "FAILED";
        this.error = truncate(error);
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            this.status = "CANCELLED";
            this.updatedAt = Instant.now();
        }
    }

    public boolean isCancelled() { return "CANCELLED".equals(status); }

    public Long getId() { return id; }
    public String getResumeId() { return resumeId; }
    public String getTargetRole() { return targetRole; }
    public String getStatus() { return status; }
    public Integer getOverallScore() { return overallScore; }
    public Integer getContentScore() { return contentScore; }
    public Integer getStructureScore() { return structureScore; }
    public Integer getSkillMatchScore() { return skillMatchScore; }
    public Integer getExpressionScore() { return expressionScore; }
    public Integer getProjectScore() { return projectScore; }
    public String getSummary() { return summary; }
    public String getStrengthsJson() { return strengthsJson; }
    public String getSuggestionsJson() { return suggestionsJson; }
    public String getIssuesJson() { return issuesJson; }
    public String getError() { return error; }
    public int getRetryCount() { return retryCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private String truncate(String message) {
        return message == null ? "resume analysis failed" : message.substring(0, Math.min(500, message.length()));
    }
}
