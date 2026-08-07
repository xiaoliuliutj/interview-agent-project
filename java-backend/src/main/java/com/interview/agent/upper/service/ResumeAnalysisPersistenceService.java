package com.interview.agent.upper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.ResumeAnalysisEntity;
import com.interview.agent.upper.repository.ResumeAnalysisRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ResumeAnalysisPersistenceService {
    private final ResumeAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    public ResumeAnalysisPersistenceService(
            ResumeAnalysisRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResumeAnalysisEntity create(String resumeId) {
        return repository.save(new ResumeAnalysisEntity(resumeId));
    }

    @Transactional
    public void markProcessing(Long id) { required(id).markProcessing(); }

    @Transactional
    public void complete(Long id, AgentResponse response) {
        Map<String, Object> output = response.output();
        if (output == null) {
            throw new BusinessException("RESUME_ANALYSIS_OUTPUT_MISSING", "下层评价结果为空");
        }
        required(id).complete(
                integer(output, "overallScore"), integer(output, "contentScore"),
                integer(output, "structureScore"), integer(output, "skillMatchScore"),
                integer(output, "expressionScore"), integer(output, "projectScore"),
                string(output, "summary"), json(output.get("strengths")),
                json(output.get("suggestions")));
    }

    @Transactional
    public void fail(Long id, String message) { required(id).fail(message); }

    public ResumeAnalysisEntity latest(String resumeId) {
        return repository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId).orElse(null);
    }

    public List<ResumeAnalysisEntity> list(String resumeId) {
        return repository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    @Transactional
    public void deleteByResumeId(String resumeId) { repository.deleteByResumeId(resumeId); }

    private ResumeAnalysisEntity required(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_NOT_FOUND", "简历评价任务不存在"));
    }

    private int integer(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "评价结果缺少 " + key);
    }

    private String string(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "评价结果缺少 " + key);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException error) { throw new BusinessException("RESUME_ANALYSIS_OUTPUT_INVALID", "评价列表无法持久化"); }
    }
}
