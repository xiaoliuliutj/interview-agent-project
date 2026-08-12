package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.KnowledgeBaseView;
import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentRagDeleteRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import com.interview.agent.upper.repository.KnowledgeBaseRepository;
import jakarta.transaction.Transactional;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeBaseService {
    public record DownloadedDocument(String filename, String contentType, byte[] content) {}

    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseIndexWorker indexWorker;
    private final KnowledgeBasePersistenceService persistence;
    private final AgentGateway agentGateway;
    private final UserIdentityResolver identity;
    private final BusinessIdGenerator idGenerator;
    private final Tika tika = new Tika();

    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            KnowledgeBaseIndexWorker indexWorker,
            KnowledgeBasePersistenceService persistence,
            AgentGateway agentGateway,
            UserIdentityResolver identity,
            BusinessIdGenerator idGenerator) {
        this.repository = repository;
        this.indexWorker = indexWorker;
        this.persistence = persistence;
        this.agentGateway = agentGateway;
        this.identity = identity;
        this.idGenerator = idGenerator;
    }

    public KnowledgeBaseView upload(MultipartFile file, String name, String category, String userId) throws IOException {
        return upload(file, name, category, userId, null, null, null, null);
    }

    public KnowledgeBaseView upload(MultipartFile file, String name, String category, String userId,
            String sourceUrl, String sourceTitle, Instant sourceFetchedAt, String sourceHash) throws IOException {
        String ownerId = identity.require(userId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("KNOWLEDGE_BASE_FILE_REQUIRED", "knowledge base file must not be empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_FILENAME_REQUIRED", "knowledge base filename is required");
        }
        String id = idGenerator.next();
        String content;
        try {
            // Plain-text and Markdown are already knowledge documents. Parsing
            // them through Tika adds an unnecessary failure point in container
            // deployments, so read them directly as UTF-8. Binary office/PDF
            // formats still use Tika's dedicated parsers.
            content = isPlainTextDocument(originalFilename, file.getContentType())
                    ? new String(file.getBytes(), StandardCharsets.UTF_8)
                    : tika.parseToString(file.getInputStream());
        } catch (Exception error) {
            throw new BusinessException("KNOWLEDGE_BASE_PARSE_FAILED", "knowledge base document parsing failed");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_CONTENT_EMPTY", "knowledge base text must not be empty");
        }
        String resolvedName = name == null || name.isBlank() ? originalFilename : name.strip();
        byte[] originalBytes = file.getBytes();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(
                id, ownerId,
                resolvedName,
                category,
                originalFilename,
                file.getSize(), file.getContentType(), content);
        entity.attachOriginalBytes(originalBytes);
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            entity.attachWebSource(sourceUrl.strip(), sourceTitle, sourceFetchedAt, sourceHash);
        }
        entity = repository.save(entity);
        try {
            indexWorker.index(entity.getId(), ownerId);
        } catch (RuntimeException error) {
            persistence.markIndexFailed(entity.getId(), error.getMessage());
            throw error;
        }
        return toView(entity);
    }

    public List<KnowledgeBaseView> list(String userId) {
        return list(userId, "time", null);
    }

    public List<KnowledgeBaseView> list(String userId, String sortBy, String vectorStatus) {
        Set<String> allowedStatuses = Set.of(
                "PENDING", "PROCESSING", "COMPLETED", "FAILED", "DELETING", "DELETE_FAILED");
        if (vectorStatus != null && !vectorStatus.isBlank() && !allowedStatuses.contains(vectorStatus)) {
            throw new BusinessException(
                    "KNOWLEDGE_BASE_STATUS_INVALID", "vectorStatus is not supported");
        }
        Comparator<KnowledgeBaseEntity> comparator = switch (sortBy == null ? "time" : sortBy) {
            case "time" -> Comparator.comparing(
                    KnowledgeBaseEntity::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "size" -> Comparator.comparingLong(KnowledgeBaseEntity::getFileSize).reversed();
            default -> throw new BusinessException(
                    "KNOWLEDGE_BASE_SORT_INVALID", "sortBy must be time or size");
        };
        return repository.findByOwnerIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .filter(item -> vectorStatus == null || vectorStatus.isBlank()
                        || vectorStatus.equals(item.getVectorStatus()))
                .sorted(comparator)
                .map(this::toView)
                .toList();
    }

    public DownloadedDocument download(long id, String userId) {
        KnowledgeBaseEntity entity = required(Long.toString(id), userId);
        String contentType = entity.getContentType() == null || entity.getContentType().isBlank()
                ? "text/plain" : entity.getContentType();
        String filename = entity.getOriginalFilename() == null || entity.getOriginalFilename().isBlank()
                ? "knowledge-base-" + id + ".txt" : entity.getOriginalFilename();
        byte[] original = entity.getOriginalBytes();
        return new DownloadedDocument(filename, contentType,
                original == null ? (entity.getContent() == null ? new byte[0]
                        : entity.getContent().getBytes(StandardCharsets.UTF_8)) : original);
    }

    public void delete(long id, String userId) {
        KnowledgeBaseEntity entity = required(Long.toString(id), userId);
        persistence.markDeleting(entity.getId());
        // 向量删除是幂等操作。即使本地 chunkCount 为 0，也必须清理下层，
        // 因为异步索引可能已写入迟到向量但尚未来得及回写该计数。
        AgentResponse response = agentGateway.deleteRag(new AgentRagDeleteRequest(
                "v1", UUID.randomUUID().toString(), "rag-delete-" + entity.getId(),
                identity.require(userId), "kb-delete-" + entity.getId(), "rag.delete", entity.getId(),
                Instant.now()));
        if (response == null || response.code() < 100 || response.code() >= 200) {
            String message = response != null && response.error() != null
                    ? response.error().message() : "lower RAG vector deletion failed";
            persistence.markDeleteFailed(entity.getId(), message);
            throw new BusinessException("KNOWLEDGE_BASE_VECTOR_DELETE_FAILED", message);
        }
        persistence.deleteMarked(entity.getId());
    }

    public List<String> categories(String userId) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .map(KnowledgeBaseEntity::getCategory)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public List<KnowledgeBaseView> byCategory(String category, String userId) {
        return repository.findByOwnerIdAndCategory(identity.require(userId), category).stream().map(this::toView).toList();
    }

    @Transactional
    public void updateCategory(long id, String category, String userId) {
        required(Long.toString(id), userId).updateCategory(category);
    }

    public List<KnowledgeBaseView> search(String keyword, String userId) {
        return repository.findByOwnerIdAndNameContainingIgnoreCase(identity.require(userId), keyword).stream().map(this::toView).toList();
    }

    public void revectorize(long id, String userId) {
        KnowledgeBaseEntity entity = required(Long.toString(id), userId);
        if (entity.hasDeletionRequest()) {
            throw new BusinessException("KNOWLEDGE_BASE_DELETING", "knowledge base is being deleted");
        }
        persistence.markIndexPending(entity.getId());
        try {
            indexWorker.index(entity.getId(), identity.require(userId));
        } catch (RuntimeException error) {
            persistence.markIndexFailed(entity.getId(), error.getMessage());
            throw error;
        }
    }

    public KnowledgeBaseEntity required(String id, String userId) {
        KnowledgeBaseEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "knowledge base not found"));
        if (!identity.require(userId).equals(entity.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "knowledge base does not belong to current user");
        }
        return entity;
    }

    private KnowledgeBaseView toView(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseView(
                Long.parseLong(entity.getId()), entity.getName(), entity.getCategory(),
                entity.getOriginalFilename(), entity.getFileSize(), entity.getContentType(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getVectorStatus(), entity.getVectorError(),
                entity.getChunkCount(), entity.getSourceUrl(), entity.getSourceTitle(),
                entity.getSourceFetchedAt(), entity.getSourceHash());
    }

    private static boolean isPlainTextDocument(String filename, String contentType) {
        String loweredName = filename.toLowerCase(java.util.Locale.ROOT);
        if (loweredName.endsWith(".md") || loweredName.endsWith(".markdown") || loweredName.endsWith(".txt")) {
            return true;
        }
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/");
    }
}
