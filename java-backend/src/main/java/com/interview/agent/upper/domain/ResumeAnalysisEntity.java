package com.interview.agent.upper.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "resume_analyses")
public class ResumeAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String resumeId;
    private String status;
    private Integer overallScore;
    private Integer contentScore;
    private Integer structureScore;
    private Integer skillMatchScore;
    private Integer expressionScore;
    private Integer projectScore;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;
    @Column(columnDefinition = "TEXT")
    private String suggestionsJson;
    @Column(columnDefinition = "TEXT")
    private String issuesJson;
    @Column(length = 500)
    private String error;
    private Instant createdAt;
    private Instant updatedAt;

    protected ResumeAnalysisEntity() {
    }

    public ResumeAnalysisEntity(String resumeId) {
        this.resumeId = resumeId;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() { this.status = "PROCESSING"; this.updatedAt = Instant.now(); }

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
        this.error = error == null ? "简历评价失败" : error.substring(0, Math.min(500, error.length()));
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getResumeId() { return resumeId; }
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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
