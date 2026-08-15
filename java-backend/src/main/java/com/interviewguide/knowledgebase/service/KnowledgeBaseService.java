package com.interviewguide.knowledgebase.service;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.common.id.BusinessIdGenerator;
import com.interviewguide.common.security.UserIdentityResolver;
import com.interviewguide.infrastructure.redis.JavaTaskStatusCache;

import com.interviewguide.knowledgebase.dto.KnowledgeBaseView;
import com.interviewguide.pythonagent.mapper.PythonAgentClient;
import com.interviewguide.pythonagent.dto.AgentRagDeleteRequest;
import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import com.interviewguide.knowledgebase.mapper.KnowledgeBaseRepository;
import org.springframework.transaction.annotation.Transactional;
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
    private final PythonAgentClient pythonAgentClient;
    private final UserIdentityResolver identity;
    private final BusinessIdGenerator idGenerator;
    private final JavaTaskStatusCache taskCache;
    private final Tika tika = new Tika();

    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            KnowledgeBaseIndexWorker indexWorker,
            KnowledgeBasePersistenceService persistence,
            PythonAgentClient pythonAgentClient,
            UserIdentityResolver identity,
            BusinessIdGenerator idGenerator,
            JavaTaskStatusCache taskCache) {
        this.repository = repository;
        this.indexWorker = indexWorker;
        this.persistence = persistence;
        this.pythonAgentClient = pythonAgentClient;
        this.identity = identity;
        this.idGenerator = idGenerator;
        this.taskCache = taskCache;
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
        return persistDocument(ownerId, resolvedName, category, originalFilename,
                file.getContentType(), content, originalBytes,
                sourceUrl, sourceTitle, sourceFetchedAt, sourceHash);
    }

    public KnowledgeBaseView uploadMarkdown(String filename, String name, String category, String userId,
            String markdown, String sourceUrl, String sourceTitle, Instant sourceFetchedAt, String sourceHash) {
        String ownerId = identity.require(userId);
        if (filename == null || filename.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_FILENAME_REQUIRED", "knowledge base filename is required");
        }
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_CONTENT_EMPTY", "knowledge base text must not be empty");
        }
        byte[] originalBytes = markdown.getBytes(StandardCharsets.UTF_8);
        String resolvedName = name == null || name.isBlank() ? filename : name.strip();
        return persistDocument(ownerId, resolvedName, category, filename, "text/markdown",
                markdown, originalBytes, sourceUrl, sourceTitle, sourceFetchedAt, sourceHash);
    }

    private KnowledgeBaseView persistDocument(String ownerId, String resolvedName, String category,
            String originalFilename, String contentType, String content, byte[] originalBytes,
            String sourceUrl, String sourceTitle, Instant sourceFetchedAt, String sourceHash) {
        String id = idGenerator.next();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(
                id, ownerId,
                resolvedName,
                category,
                originalFilename,
                originalBytes.length, contentType, content);
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
        /*
        // 闁告碍鍨块崳娲礆閻樼粯鐝熼柡鍕靛灠缁犳挾绮垫径瀣儥濞达絾鍤庨埀顒€鍊稿畵鍡樻媴閹稿孩鎷遍柛?chunkCount �?0闁挎稑濂旂弧鍐疀閸涙番鈧繐銆掗崨顖涘€炲☉鎾愁儏閻即鏁?        // 闁搞儳濮崇拹鐔奉嚕閸屾侗鍔勭紒渚垮灩缁扁晠宕ｉ婵嗗幋鐎瑰憡褰冮崯鎾诲礂閵夈劎绠块柛鎺撴緲閹粓鏌岃箛搴ｇɑ閻忓繑纰嶅﹢顓㈠级閵夈儳绻侀柛娆忥工濞叉牠宕樺▎鎺濆殙閻犱讲鍓濋弳鐔煎�?        AgentResponse response = pythonAgentClient.deleteRag(new AgentRagDeleteRequest(
                "v1", UUID.randomUUID().toString(), "rag-delete-" + entity.getId(),
                identity.require(userId), "kb-delete-" + entity.getId(), "rag.delete", entity.getId(),
                Instant.now()));
        */
        AgentResponse response = pythonAgentClient.deleteRag(new AgentRagDeleteRequest(
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
        KnowledgeBaseEntity entity = required(Long.toString(id), userId);
        entity.updateCategory(category);
        repository.save(entity);
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
        var cached = taskCache.knowledgeBaseIndex(entity.getId());
        String vectorStatus = cached.map(value -> value.get("status"))
                .filter(String.class::isInstance).map(String.class::cast).orElse(entity.getVectorStatus());
        String vectorError = cached.map(value -> value.get("error"))
                .filter(String.class::isInstance).map(String.class::cast).orElse(entity.getVectorError());
        return new KnowledgeBaseView(
                Long.parseLong(entity.getId()), entity.getName(), entity.getCategory(),
                entity.getOriginalFilename(), entity.getFileSize(), entity.getContentType(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                vectorStatus, vectorError,
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
