package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import com.interviewguide.knowledgebase.domain.KnowledgeBaseResponse;
import com.interviewguide.knowledgebase.mapper.KnowledgeBaseMapper;
import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.common.exception.PythonAgentException;
import com.interviewguide.common.messaging.AgentWorkTaskMessage;
import com.interviewguide.common.messaging.RabbitTaskConfiguration;
import com.interviewguide.common.redis.JavaTaskStatusCache;
import com.interviewguide.pythonagent.domain.AgentRagDeleteRequest;
import com.interviewguide.pythonagent.domain.AgentRagIndexRequest;
import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.pythonagent.mapper.PythonAgentMapper;
import com.interviewguide.utils.file.DocumentContentUtil;
import com.interviewguide.utils.id.BusinessIdGenerator;
import com.interviewguide.common.security.UserIdentityResolver;
import com.interviewguide.utils.transaction.TransactionAfterCommitUtil;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Coordinates every knowledge-base HTTP operation and its asynchronous vector-index lifecycle. */
@Service
public class KnowledgeBaseLifecycleService {
    /** Limits public filtering to the vector states represented by the domain entity. */
    private static final Set<String> VECTOR_STATUSES = Set.of(
            "PENDING", "PROCESSING", "COMPLETED", "FAILED", "DELETING", "DELETE_FAILED");
    /** Reads and writes knowledge-base records. */
    private final KnowledgeBaseMapper mapper;
    /** Calls the Python RAG service through the dedicated integration adapter. */
    private final PythonAgentMapper pythonAgentClient;
    /** Publishes the asynchronous RAG indexing message. */
    private final RabbitTemplate rabbitTemplate;
    /** Maintains Java-owned transient task snapshots in Redis. */
    private final JavaTaskStatusCache taskCache;
    /** Validates the caller identity used as the record owner. */
    private final UserIdentityResolver identity;
    /** Generates persistent business ids. */
    private final BusinessIdGenerator idGenerator;

    /** Builds the single knowledge-base service from its direct infrastructure dependencies. */
    public KnowledgeBaseLifecycleService(KnowledgeBaseMapper mapper, PythonAgentMapper pythonAgentClient,
                                RabbitTemplate rabbitTemplate, JavaTaskStatusCache taskCache,
                                UserIdentityResolver identity, BusinessIdGenerator idGenerator) {
        // Save the mapper used by all database operations in this module.
        this.mapper = mapper;
        // Save the only Java-to-Python integration port.
        this.pythonAgentClient = pythonAgentClient;
        // Save the Rabbit publisher for deferred indexing work.
        this.rabbitTemplate = rabbitTemplate;
        // Save the Redis task-state cache used by polling clients.
        this.taskCache = taskCache;
        // Save the user-header validator.
        this.identity = identity;
        // Save the business-id generator.
        this.idGenerator = idGenerator;
    }

    /** Stores an uploaded file and requests asynchronous Python vector indexing. */
    @Transactional
    public KnowledgeBaseResponse upload(MultipartFile file, String name, String category, String userId,
                                        String sourceUrl, String sourceTitle, Instant sourceFetchedAt,
                                        String sourceHash) throws IOException {
        // Resolve the record owner before accepting the upload.
        String ownerId = identity.require(userId);
        // Reject a missing file before extracting bytes.
        if (file == null || file.isEmpty()) {
            throw new BusinessException("KNOWLEDGE_BASE_FILE_REQUIRED", "knowledge base file must not be empty");
        }
        // Keep the original filename because it becomes download metadata and Python provenance.
        String originalFilename = file.getOriginalFilename();
        // A document without a filename cannot be represented safely in the record.
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_FILENAME_REQUIRED", "knowledge base filename is required");
        }
        // Extract searchable text through the generic document parser.
        String content = DocumentContentUtil.extractText(file, originalFilename);
        // Empty extracted text cannot be indexed meaningfully.
        if (content == null || content.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_CONTENT_EMPTY", "knowledge base text must not be empty");
        }
        // Use the original name when the client does not provide a display name.
        String resolvedName = name == null || name.isBlank() ? originalFilename : name.strip();
        // Retain the original source bytes so download does not reconstruct binary content.
        byte[] originalBytes = file.getBytes();
        // Build the module entity with the generated id and extracted content.
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(idGenerator.next(), ownerId, resolvedName, category,
                originalFilename, originalBytes.length, file.getContentType(), content);
        // Attach the exact uploaded data to the persistent domain record.
        entity.attachOriginalBytes(originalBytes);
        // Preserve optional web-source provenance when this upload originated from web crawling.
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            entity.attachWebSource(sourceUrl.strip(), sourceTitle, sourceFetchedAt, sourceHash);
        }
        // Persist the PENDING record before sending its asynchronous work message.
        mapper.save(entity);
        // Publish only after the database transaction commits.
        TransactionAfterCommitUtil.run(() -> enqueueIndex(entity.getId(), ownerId));
        // Return the business response calculated from this module's record.
        return toResponse(entity);
    }

    /** Stores Markdown assembled by the web-crawl business flow and requests indexing. */
    @Transactional
    public KnowledgeBaseResponse uploadMarkdown(String filename, String name, String category, String userId,
                                                String markdown, String sourceUrl, String sourceTitle,
                                                Instant sourceFetchedAt, String sourceHash) {
        // Resolve the request owner once for both storage and message publication.
        String ownerId = identity.require(userId);
        // Require a safe source filename for the stored Markdown document.
        if (filename == null || filename.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_FILENAME_REQUIRED", "knowledge base filename is required");
        }
        // Require content because empty Markdown would create an unusable RAG document.
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException("KNOWLEDGE_BASE_CONTENT_EMPTY", "knowledge base text must not be empty");
        }
        // Encode the Markdown once to retain both file bytes and the stored byte size.
        byte[] originalBytes = markdown.getBytes(StandardCharsets.UTF_8);
        // Prefer the page title supplied by the crawler as the display name.
        String resolvedName = name == null || name.isBlank() ? filename : name.strip();
        // Create the business record in the same way as a direct upload.
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(idGenerator.next(), ownerId, resolvedName, category,
                filename, originalBytes.length, "text/markdown", markdown);
        // Store the original Markdown byte sequence.
        entity.attachOriginalBytes(originalBytes);
        // Store the crawler provenance used for traceability.
        entity.attachWebSource(sourceUrl, sourceTitle, sourceFetchedAt, sourceHash);
        // Persist the PENDING document before publishing its index request.
        mapper.save(entity);
        // Delay publication until the database transaction succeeds.
        TransactionAfterCommitUtil.run(() -> enqueueIndex(entity.getId(), ownerId));
        // Return the newly stored document response.
        return toResponse(entity);
    }

    /** Lists the caller's documents with the requested supported sort and vector-state filter. */
    public List<KnowledgeBaseResponse> list(String userId, String sortBy, String vectorStatus) {
        // Validate the optional vector-state filter before issuing the database query.
        if (vectorStatus != null && !vectorStatus.isBlank() && !VECTOR_STATUSES.contains(vectorStatus)) {
            throw new BusinessException("KNOWLEDGE_BASE_STATUS_INVALID", "vectorStatus is not supported");
        }
        // Choose the sort comparator defined by the public API.
        Comparator<KnowledgeBaseEntity> comparator = switch (sortBy == null ? "time" : sortBy) {
            case "time" -> Comparator.comparing(KnowledgeBaseEntity::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "size" -> Comparator.comparingLong(KnowledgeBaseEntity::getFileSize).reversed();
            default -> throw new BusinessException("KNOWLEDGE_BASE_SORT_INVALID", "sortBy must be time or size");
        };
        // Query only owned records, then apply filter, order, and response conversion.
        return mapper.findByOwnerIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .filter(item -> vectorStatus == null || vectorStatus.isBlank() || vectorStatus.equals(item.getVectorStatus()))
                .sorted(comparator).map(this::toResponse).toList();
    }

    /** Returns an owned document entity for the controller's file-download response. */
    public KnowledgeBaseEntity download(long id, String userId) {
        // Load and authorize the requested record inside this module service.
        return requiredOwned(Long.toString(id), userId);
    }

    /** Deletes the lower Python vector data before removing the stored document. */
    @Transactional
    public void delete(long id, String userId) {
        // Load and authorise the document before its state changes.
        KnowledgeBaseEntity entity = requiredOwned(Long.toString(id), userId);
        // Persist the deletion intent so late index messages stop safely.
        entity.markDeleting();
        mapper.save(entity);
        // Reflect the deletion state in Redis after the database transaction commits.
        TransactionAfterCommitUtil.run(() -> taskCache.updateKnowledgeBaseIndex(entity.getId(), entity.getVectorStatus(), entity.getVectorError()));
        // Request lower-layer vector deletion synchronously to avoid orphaned vectors.
        AgentResponse response = pythonAgentClient.deleteRag(new AgentRagDeleteRequest(
                "v1", UUID.randomUUID().toString(), "rag-delete-" + entity.getId(), identity.require(userId),
                "kb-delete-" + entity.getId(), "rag.delete", entity.getId(), Instant.now()));
        // Preserve the record with a failure state when Python cannot remove its vectors.
        if (response == null || response.code() < 100 || response.code() >= 200) {
            String message = response != null && response.error() != null
                    ? response.error().message() : "lower RAG vector deletion failed";
            entity.markDeleteFailed(message);
            mapper.save(entity);
            TransactionAfterCommitUtil.run(() -> taskCache.updateKnowledgeBaseIndex(entity.getId(), entity.getVectorStatus(), entity.getVectorError()));
            throw new BusinessException("KNOWLEDGE_BASE_VECTOR_DELETE_FAILED", message);
        }
        // Remove the durable row only after Python confirms deletion.
        mapper.delete(entity);
        // Remove the asynchronous-state snapshot after database deletion commits.
        TransactionAfterCommitUtil.run(() -> taskCache.removeKnowledgeBaseIndex(entity.getId()));
    }

    /** Returns distinct non-empty categories owned by the caller. */
    public List<String> categories(String userId) {
        // Select and project the caller's document categories.
        return mapper.findByOwnerIdOrderByCreatedAtDesc(identity.require(userId)).stream()
                .map(KnowledgeBaseEntity::getCategory).filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    /** Returns caller-owned documents in one category. */
    public List<KnowledgeBaseResponse> byCategory(String category, String userId) {
        // Let the mapper constrain both owner and category.
        return mapper.findByOwnerIdAndCategory(identity.require(userId), category).stream().map(this::toResponse).toList();
    }

    /** Updates one owned document category. */
    @Transactional
    public void updateCategory(long id, String category, String userId) {
        // Load the owned entity before its domain mutation.
        KnowledgeBaseEntity entity = requiredOwned(Long.toString(id), userId);
        // Apply the category mutation defined by the entity.
        entity.updateCategory(category);
        // Persist the changed record through the sole module mapper.
        mapper.save(entity);
    }

    /** Searches caller-owned document names. */
    public List<KnowledgeBaseResponse> search(String keyword, String userId) {
        // Delegate case-insensitive name matching to the mapper.
        return mapper.findByOwnerIdAndNameContainingIgnoreCase(identity.require(userId), keyword).stream()
                .map(this::toResponse).toList();
    }

    /** Returns aggregate counts for the knowledge-base list screen. */
    public Map<String, Object> stats(String userId) {
        // Reuse the public list semantics so status counts match visible documents.
        List<KnowledgeBaseResponse> items = list(userId, "time", null);
        // Calculate all list-screen totals from the same response set.
        return Map.of("totalCount", items.size(),
                "completedCount", items.stream().filter(item -> "COMPLETED".equals(item.vectorStatus())).count(),
                "processingCount", items.stream().filter(item -> "PROCESSING".equals(item.vectorStatus()) || "PENDING".equals(item.vectorStatus())).count(),
                "failedCount", items.stream().filter(item -> "FAILED".equals(item.vectorStatus()) || "DELETE_FAILED".equals(item.vectorStatus())).count());
    }

    /** Marks an owned document pending and sends it through the normal asynchronous index path. */
    @Transactional
    public void revectorize(long id, String userId) {
        // Load and authorise the requested document.
        KnowledgeBaseEntity entity = requiredOwned(Long.toString(id), userId);
        // Reject attempts to recreate vectors for a deletion request.
        if (entity.hasDeletionRequest()) {
            throw new BusinessException("KNOWLEDGE_BASE_DELETING", "knowledge base is being deleted");
        }
        // Reset the persistent vector lifecycle to PENDING.
        entity.markVectorPending();
        mapper.save(entity);
        // Publish the new RAG task only after the pending state has committed.
        TransactionAfterCommitUtil.run(() -> enqueueIndex(entity.getId(), identity.require(userId)));
    }

    /** Processes one Rabbit index message while remaining in the sole module business service. */
    @Transactional
    public void processIndex(String knowledgeBaseId, String userId) {
        // Ignore messages for a document deleted after publication.
        KnowledgeBaseEntity entity = mapper.findById(knowledgeBaseId).orElse(null);
        if (entity == null || entity.hasDeletionRequest()) {
            return;
        }
        // Reject a malformed message that claims a different owner.
        if (!userId.equals(entity.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "knowledge base does not belong to current user");
        }
        // Acquire the state transition by marking the document as processing.
        if (!entity.markVectorProcessing()) {
            return;
        }
        // Persist the processing state before calling Python.
        mapper.save(entity);
        // Publish polling state after successful persistence.
        TransactionAfterCommitUtil.run(() -> taskCache.updateKnowledgeBaseIndex(entity.getId(), entity.getVectorStatus(), entity.getVectorError()));
        try {
            // Build the RAG index request using only this stored knowledge-base document.
            AgentResponse response = pythonAgentClient.indexRag(new AgentRagIndexRequest(
                    "v1", UUID.randomUUID().toString(), "rag-index-" + entity.getId(), userId,
                    "kb-" + entity.getId(), "rag.index", entity.getContent(), List.of(entity.getId()),
                    entity.getId(), entity.getOriginalFilename(), Instant.now()));
            // Handle a non-success lower response without allowing invalid vector state to persist.
            if (response == null || response.code() < 100 || response.code() >= 200) {
                String message = response != null && response.error() != null
                        ? response.error().message() : "lower RAG indexing failed";
                entity.markVectorFailed(message);
                mapper.save(entity);
                TransactionAfterCommitUtil.run(() -> taskCache.updateKnowledgeBaseIndex(entity.getId(), entity.getVectorStatus(), entity.getVectorError()));
                if (response != null && response.retryable()) {
                    throw new PythonAgentException(message, null, true);
                }
                return;
            }
            // Refresh the row because it may have been deleted while Python was processing.
            KnowledgeBaseEntity latest = mapper.findById(knowledgeBaseId).orElse(null);
            if (latest == null || latest.hasDeletionRequest()) {
                return;
            }
            // Record the chunk count returned by the successful Python RAG operation.
            latest.markVectorized(Integer.parseInt(response.answer()));
            mapper.save(latest);
            TransactionAfterCommitUtil.run(() -> taskCache.updateKnowledgeBaseIndex(latest.getId(), latest.getVectorStatus(), latest.getVectorError()));
        } catch (RuntimeException error) {
            // Refresh again so a deletion cannot be overwritten by a failed index state.
            KnowledgeBaseEntity latest = mapper.findById(knowledgeBaseId).orElse(null);
            if (latest != null && !latest.hasDeletionRequest()) {
                latest.markVectorFailed(error.getMessage());
                mapper.save(latest);
                TransactionAfterCommitUtil.run(() -> taskCache.updateKnowledgeBaseIndex(latest.getId(), latest.getVectorStatus(), latest.getVectorError()));
            }
            // A business or non-retryable gateway error must be acknowledged rather than retried.
            if (error instanceof BusinessException || error instanceof PythonAgentException gateway && !gateway.retryable()) {
                return;
            }
            // Let RabbitMQ retry only transient lower-service failures.
            throw error;
        }
    }

    /** Publishes one asynchronous index request after a document write commits. */
    private void enqueueIndex(String knowledgeBaseId, String userId) {
        // Keep queue protocol details in common messaging while the business decision remains here.
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE, RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.KNOWLEDGE_BASE_INDEX, knowledgeBaseId, userId));
    }

    /** Loads a record and checks its ownership for every caller-visible operation. */
    private KnowledgeBaseEntity requiredOwned(String id, String userId) {
        // Convert a missing database row to the module's public not-found error.
        KnowledgeBaseEntity entity = mapper.findById(id)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "knowledge base not found"));
        // Compare the durable owner with the validated header identity.
        if (!identity.require(userId).equals(entity.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "knowledge base does not belong to current user");
        }
        // Return the authorised domain entity.
        return entity;
    }

    /** Converts an entity into this module's API data while applying a newer Redis task snapshot. */
    private KnowledgeBaseResponse toResponse(KnowledgeBaseEntity entity) {
        // Prefer a post-commit task update when Redis has a newer status than the read row.
        var cached = taskCache.knowledgeBaseIndex(entity.getId());
        // Extract a valid cached status if one exists.
        String status = cached.map(value -> value.get("status")).filter(String.class::isInstance)
                .map(String.class::cast).orElse(entity.getVectorStatus());
        // Extract the matching cached error when present.
        String error = cached.map(value -> value.get("error")).filter(String.class::isInstance)
                .map(String.class::cast).orElse(entity.getVectorError());
        // Construct the response directly from the domain state.
        return new KnowledgeBaseResponse(Long.parseLong(entity.getId()), entity.getName(), entity.getCategory(),
                entity.getOriginalFilename(), entity.getFileSize(), entity.getContentType(), entity.getCreatedAt(),
                entity.getUpdatedAt(), status, error, entity.getChunkCount(), entity.getSourceUrl(),
                entity.getSourceTitle(), entity.getSourceFetchedAt(), entity.getSourceHash());
    }
}
