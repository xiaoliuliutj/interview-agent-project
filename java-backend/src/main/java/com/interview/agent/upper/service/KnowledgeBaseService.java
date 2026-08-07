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
    public record DownloadedDocument(String filename, String contentType, byte[] content) {}

    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseIndexWorker indexWorker;
    private final KnowledgeBasePersistenceService persistence;
    private final AgentGateway agentGateway;
    private final ObjectMapper objectMapper;
    private final UserIdentityResolver identity;
    private final BusinessIdGenerator idGenerator;
    private final Tika tika = new Tika();

    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            KnowledgeBaseIndexWorker indexWorker,
            KnowledgeBasePersistenceService persistence,
            AgentGateway agentGateway,
            ObjectMapper objectMapper,
            UserIdentityResolver identity,
            BusinessIdGenerator idGenerator) {
        this.repository = repository;
        this.indexWorker = indexWorker;
        this.persistence = persistence;
        this.agentGateway = agentGateway;
        this.objectMapper = objectMapper;
        this.identity = identity;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public KnowledgeBaseView upload(MultipartFile file, String name, String category, String userId) throws IOException {
        String ownerId = identity.require(userId);
        String id = idGenerator.next();
        String content;
        try {
            content = tika.parseToString(file.getInputStream());
        } catch (Exception error) {
            throw new BusinessException("KNOWLEDGE_BASE_PARSE_FAILED", "知识库文件解析失败");
        }
        KnowledgeBaseEntity entity = repository.save(new KnowledgeBaseEntity(
                id, ownerId,
                name == null || name.isBlank() ? file.getOriginalFilename() : name,
                category,
                file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                        ? "knowledge-base-" + id : file.getOriginalFilename(),
                file.getSize(),
                file.getContentType(),
                content));
        entity.attachOriginalBytes(file.getBytes());
        entity = repository.save(entity);
        indexWorker.index(entity.getId(), ownerId);
        return toView(entity);
    }

    public List<KnowledgeBaseView> list(String userId) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(identity.require(userId)).stream().map(this::toView).toList();
    }

    public KnowledgeBaseView get(long id, String userId) { return toView(required(Long.toString(id), userId)); }

    public DownloadedDocument download(long id, String userId) {
        KnowledgeBaseEntity entity = required(Long.toString(id), userId);
        String contentType = entity.getContentType() == null || entity.getContentType().isBlank()
                ? "text/plain" : entity.getContentType();
        String filename = entity.getOriginalFilename() == null || entity.getOriginalFilename().isBlank()
                ? "knowledge-base-" + id + ".txt" : entity.getOriginalFilename();
        byte[] original = entity.getOriginalBytes();
        return new DownloadedDocument(filename, contentType,
                original == null ? (entity.getContent() == null ? new byte[0]
                        : entity.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8)) : original);
    }

    @Transactional
    public void delete(long id, String userId) { repository.delete(required(Long.toString(id), userId)); }

    public List<String> categories(String userId) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(identity.require(userId)).stream().map(KnowledgeBaseEntity::getCategory)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public List<KnowledgeBaseView> byCategory(String category, String userId) {
        return repository.findByOwnerIdAndCategory(identity.require(userId), category).stream().map(this::toView).toList();
    }

    public List<KnowledgeBaseView> uncategorized(String userId) {
        return repository.findByOwnerIdAndCategoryIsNull(identity.require(userId)).stream().map(this::toView).toList();
    }

    @Transactional
    public void updateCategory(long id, String category, String userId) { required(Long.toString(id), userId).updateCategory(category); }

    public List<KnowledgeBaseView> search(String keyword, String userId) {
        return repository.findByOwnerIdAndNameContainingIgnoreCase(identity.require(userId), keyword).stream().map(this::toView).toList();
    }

    public KnowledgeBaseQueryResponse query(KnowledgeBaseQueryRequest request, String userId) {
        if (request.knowledgeBaseIds() == null || request.knowledgeBaseIds().isEmpty()) {
            throw new BusinessException("KNOWLEDGE_BASE_IDS_REQUIRED", "请选择至少一个知识库");
        }
        List<String> ids = request.knowledgeBaseIds().stream().map(String::valueOf).toList();
        AgentResponse response = agentGateway.searchRag(new AgentRagRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "kb-query", "rag.search", request.question(), ids,
                "KNOWLEDGE_BASE_QUERY"));
        if (response.code() < 100 || response.code() >= 200) {
            throw new BusinessException("RAG_QUERY_FAILED", "下层 RAG 检索失败");
        }
        ids.forEach(id -> persistence.incrementQuestionCount(required(id, userId).getId()));
        return new KnowledgeBaseQueryResponse(
                extractAgentAnswer(response.answer()),
                request.knowledgeBaseIds().getFirst(),
                required(ids.getFirst(), userId).getName());
    }

    public void revectorize(long id, String userId) {
        KnowledgeBaseEntity entity = required(Long.toString(id), userId);
        indexWorker.index(entity.getId(), identity.require(userId));
    }

    private String extractAgentAnswer(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("RAG_RESPONSE_INVALID", "lower RAG returned an empty answer");
        }
        // Python Agent 已经基于证据生成答案，Java 不解析或拼接命中 chunk。
        return raw;
    }

    public KnowledgeBaseEntity required(String id, String userId) {
        KnowledgeBaseEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        if (!identity.require(userId).equals(entity.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "无权访问该知识库");
        }
        return entity;
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
