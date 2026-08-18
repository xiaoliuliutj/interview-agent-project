package com.interviewguide.utils.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates and serializes the typed values returned by the Python resume-analysis API.
 */
@Component
public class AgentOutputUtil {
    /** Stores the shared JSON codec used to persist Python arrays and objects. */
    private final ObjectMapper objectMapper;

    /** Creates the output utility with Spring's configured JSON codec. */
    public AgentOutputUtil(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** Reads one required numeric score from a Python output map. */
    public int integer(Map<String, Object> output, String key) {
        // Reject strings and missing fields so invalid remote data cannot be persisted as a completed task.
        Object value = output.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "resume analysis output misses numeric field: " + key);
    }

    /** Reads one required non-blank text field from a Python output map. */
    public String string(Map<String, Object> output, String key) {
        // Keep summary validation identical for every Python completion path.
        Object value = output.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "resume analysis output misses text field: " + key);
    }

    /** Serializes an optional Python collection before it is stored in a JSON column. */
    public String json(Object value) {
        // Normalize null arrays to an empty array for a stable database representation.
        try { return objectMapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException error) {
            throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "resume analysis output JSON is invalid");
        }
    }
}
