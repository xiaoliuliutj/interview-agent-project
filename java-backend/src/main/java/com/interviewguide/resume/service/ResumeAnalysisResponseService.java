package com.interviewguide.resume.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.resume.domain.ResumeAnalysisEntity;
import com.interviewguide.resume.domain.ResumeAnalysisResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Converts persisted resume-analysis data and Redis snapshots into API response data.
 */
@Service
public class ResumeAnalysisResponseService {
    /** Stores the shared JSON codec used by both database and cache conversion. */
    private final ObjectMapper objectMapper;

    /** Creates the conversion utility with Spring's configured JSON codec. */
    public ResumeAnalysisResponseService(ObjectMapper objectMapper) {
        // Keep JSON parsing outside the business service.
        this.objectMapper = objectMapper;
    }

    /** Converts a database entity into the response returned by resume endpoints. */
    public ResumeAnalysisResponse fromEntity(ResumeAnalysisEntity entity) {
        // Copy scalar columns and decode the three JSON columns in one place.
        return new ResumeAnalysisResponse(entity.getId(), entity.getStatus(), entity.getOverallScore(),
                entity.getContentScore(), entity.getStructureScore(), entity.getSkillMatchScore(),
                entity.getExpressionScore(), entity.getProjectScore(), entity.getSummary(), entity.getUpdatedAt(),
                stringList(entity.getStrengthsJson()), stringList(entity.getSuggestionsJson()),
                mapList(entity.getIssuesJson()), entity.getError());
    }

    /** Converts the Redis task snapshot into the same response shape as a database entity. */
    public ResumeAnalysisResponse fromCache(Map<String, Object> value) {
        // Redis serializes numbers and timestamps as generic JSON values.
        return new ResumeAnalysisResponse(number(value.get("analysisId")).longValue(), string(value.get("status")),
                integerOrNull(value.get("overallScore")), integerOrNull(value.get("contentScore")),
                integerOrNull(value.get("structureScore")), integerOrNull(value.get("skillMatchScore")),
                integerOrNull(value.get("expressionScore")), integerOrNull(value.get("projectScore")),
                nullableString(value.get("summary")), instant(value.get("updatedAt")),
                stringList(nullableString(value.get("strengthsJson"))),
                stringList(nullableString(value.get("suggestionsJson"))),
                mapList(nullableString(value.get("issuesJson"))), nullableString(value.get("error")));
    }

    /** Returns a bounded message suitable for persisting an asynchronous failure. */
    public String failureMessage(RuntimeException error) {
        // Avoid letting an unbounded remote stack trace occupy a task status field.
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }

    /** Converts a generic value to a numeric fallback used by old cache snapshots. */
    private Number number(Object value) { return value instanceof Number number ? number : 0L; }

    /** Converts a generic value to an optional integer score. */
    private Integer integerOrNull(Object value) { return value instanceof Number number ? number.intValue() : null; }

    /** Converts a generic status value and keeps pending as the backward-compatible default. */
    private String string(Object value) { return value instanceof String text ? text : "PENDING"; }

    /** Returns a string only when the snapshot actually contains a string. */
    private String nullableString(Object value) { return value instanceof String text ? text : null; }

    /** Parses the persisted UTC timestamp or falls back to the current instant for malformed old cache data. */
    private Instant instant(Object value) {
        // Cache corruption must not make the polling endpoint fail.
        if (!(value instanceof String text)) return Instant.now();
        try { return Instant.parse(text); } catch (Exception ignored) { return Instant.now(); }
    }

    /** Decodes a JSON string array and returns an empty immutable list for absent or invalid history. */
    private List<String> stringList(String raw) {
        // Database JSON is optional while a task is pending.
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    /** Decodes the stored issue object array without leaking malformed legacy values to callers. */
    private List<Map<String, Object>> mapList(String raw) {
        // Use the same empty fallback as string lists.
        if (raw == null || raw.isBlank()) return List.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }
}
