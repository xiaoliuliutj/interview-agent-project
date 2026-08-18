package com.interviewguide.common.redis;

import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Cache-only task snapshots. PostgreSQL remains the authoritative task record. */
@Component
public class JavaTaskStatusCache {
    private static final Duration TTL = Duration.ofHours(24);
    private final JavaRedisStore store;

    public JavaTaskStatusCache(JavaRedisStore store) { this.store = store; }

    public void updateResumeAnalysis(ResumeAnalysisEntity analysis) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("analysisId", analysis.getId());
        snapshot.put("resumeId", analysis.getResumeId());
        snapshot.put("status", analysis.getStatus());
        snapshot.put("overallScore", analysis.getOverallScore());
        snapshot.put("contentScore", analysis.getContentScore());
        snapshot.put("structureScore", analysis.getStructureScore());
        snapshot.put("skillMatchScore", analysis.getSkillMatchScore());
        snapshot.put("expressionScore", analysis.getExpressionScore());
        snapshot.put("projectScore", analysis.getProjectScore());
        snapshot.put("summary", analysis.getSummary());
        snapshot.put("strengthsJson", analysis.getStrengthsJson());
        snapshot.put("suggestionsJson", analysis.getSuggestionsJson());
        snapshot.put("issuesJson", analysis.getIssuesJson());
        snapshot.put("error", analysis.getError());
        snapshot.put("updatedAt", (analysis.getUpdatedAt() == null ? Instant.now() : analysis.getUpdatedAt()).toString());
        store.putJson("java:task:resume-analysis:" + analysis.getId(), snapshot, TTL);
        store.putJson("java:task:resume-analysis:latest:" + analysis.getResumeId(), snapshot, TTL);
    }

    public void updateKnowledgeBaseIndex(String knowledgeBaseId, String status, String error) {
        store.putJson("java:task:rag-index:" + knowledgeBaseId,
                Map.of("knowledgeBaseId", knowledgeBaseId, "status", status, "error", error == null ? "" : error,
                        "updatedAt", Instant.now().toString()), TTL);
    }

    public Optional<Map<String, Object>> resumeAnalysis(Long analysisId) {
        return store.getJson("java:task:resume-analysis:" + analysisId);
    }

    public Optional<Map<String, Object>> latestResumeAnalysis(String resumeId) {
        return store.getJson("java:task:resume-analysis:latest:" + resumeId);
    }

    public Optional<Map<String, Object>> knowledgeBaseIndex(String knowledgeBaseId) {
        return store.getJson("java:task:rag-index:" + knowledgeBaseId);
    }

    public void removeKnowledgeBaseIndex(String knowledgeBaseId) {
        store.delete("java:task:rag-index:" + knowledgeBaseId);
    }

    public void removeLatestResumeAnalysis(String resumeId) {
        store.delete("java:task:resume-analysis:latest:" + resumeId);
    }
}
