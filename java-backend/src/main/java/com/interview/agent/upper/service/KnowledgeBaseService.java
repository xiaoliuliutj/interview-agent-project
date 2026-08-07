package com.interview.agent.upper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentRagRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.api.dto.KnowledgeBaseQueryRequest;
import com.interview.agent.upper.api.dto.KnowledgeBaseQueryResponse;
import com.interview.agent.upper.api.dto.KnowledgeBaseView;
import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import com.interview.agent.upper.repository.KnowledgeBaseRepository;
import jakarta.transaction.Transactional;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeBaseService {
    public record DownloadedDocument(String filename, String contentType, String content) {}

    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseIndexWorker indexWorker;
    private final KnowledgeBasePersistenceService persistence;
    private final AgentGateway agentGateway;
    private final ObjectMapper objectMapper;
    private final Tika tika = new Tika();

    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            KnowledgeBaseIndexWorker indexWorker,
            KnowledgeBasePersistenceService persistence,
            AgentGateway agentGateway,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.indexWorker = indexWorker;
        this.persistence = persistence;
        this.agentGateway = agentGateway;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public KnowledgeBaseView upload(MultipartFile file, String name, String category) throws IOException {
        String id = Long.toString(System.currentTimeMillis());
        String content;
        try {
            content = tika.parseToString(file.getInputStream());
        } catch (Exception error) {
            throw new BusinessException("KNOWLEDGE_BASE_PARSE_FAILED", "知识库文件解析失败");
        }
        KnowledgeBaseEntity entity = repository.save(new KnowledgeBaseEntity(
                id,
                name == null || name.isBlank() ? file.getOriginalFilename() : name,
                category,
                file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename(),
                file.getSize(),
                file.getContentType(),
                content));
        indexWorker.index(entity);
        return toView(entity);
    }

    public List<KnowledgeBaseView> list() {
        return repository.findAll().stream().map(this::toView).toList();
    }

    public KnowledgeBaseView get(long id) { return toView(required(Long.toString(id))); }

    public DownloadedDocument download(long id) {
        KnowledgeBaseEntity entity = required(Long.toString(id));
        String contentType = entity.getContentType() == null || entity.getContentType().isBlank()
                ? "text/plain" : entity.getContentType();
        String filename = entity.getOriginalFilename() == null || entity.getOriginalFilename().isBlank()
                ? "knowledge-base-" + id + ".txt" : entity.getOriginalFilename();
        return new DownloadedDocument(
                filename, contentType, entity.getContent() == null ? "" : entity.getContent());
    }

    @Transactional
    public void delete(long id) { repository.delete(required(Long.toString(id))); }

    public List<String> categories() {
        return repository.findAll().stream().map(KnowledgeBaseEntity::getCategory)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public List<KnowledgeBaseView> byCategory(String category) {
        return repository.findByCategory(category).stream().map(this::toView).toList();
    }

    public List<KnowledgeBaseView> uncategorized() {
        return repository.findByCategoryIsNull().stream().map(this::toView).toList();
    }

    @Transactional
    public void updateCategory(long id, String category) { required(Long.toString(id)).updateCategory(category); }

    public List<KnowledgeBaseView> search(String keyword) {
        return repository.findByNameContainingIgnoreCase(keyword).stream().map(this::toView).toList();
    }

    public KnowledgeBaseQueryResponse query(KnowledgeBaseQueryRequest request) {
        if (request.knowledgeBaseIds() == null || request.knowledgeBaseIds().isEmpty()) {
            throw new BusinessException("KNOWLEDGE_BASE_IDS_REQUIRED", "请选择至少一个知识库");
        }
        List<String> ids = request.knowledgeBaseIds().stream().map(String::valueOf).toList();
        AgentResponse response = agentGateway.searchRag(new AgentRagRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "system", "kb-query", "rag.search", request.question(), ids,
                "KNOWLEDGE_BASE_QUERY"));
        if (response.code() < 100 || response.code() >= 200) {
            throw new BusinessException("RAG_QUERY_FAILED", "下层 RAG 检索失败");
        }
        ids.forEach(persistence::incrementQuestionCount);
        return new KnowledgeBaseQueryResponse(
                extractAnswer(response.answer()),
                request.knowledgeBaseIds().getFirst(),
                required(ids.getFirst()).getName());
    }

    public void revectorize(long id) {
        KnowledgeBaseEntity entity = required(Long.toString(id));
        indexWorker.index(entity);
    }

    private String extractAnswer(String raw) {
        try {
            List<Map<String, Object>> chunks = objectMapper.readValue(raw, new TypeReference<>() { });
            return chunks.stream().map(item -> String.valueOf(item.get("content")))
                    .reduce((left, right) -> left + "\n\n---\n\n" + right).orElse("未检索到相关资料");
        } catch (Exception error) {
            throw new BusinessException("RAG_RESPONSE_INVALID", "下层 RAG 返回格式无效");
        }
    }

    private KnowledgeBaseEntity required(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
    }

    private KnowledgeBaseView toView(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseView(
                Long.parseLong(entity.getId()), entity.getName(), entity.getCategory(),
                entity.getOriginalFilename(), entity.getFileSize(), entity.getContentType(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getAccessCount(),
                entity.getQuestionCount(), entity.getVectorStatus(), entity.getVectorError(),
                entity.getChunkCount());
    }
}
