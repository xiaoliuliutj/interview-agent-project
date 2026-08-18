package com.interviewguide.knowledgebase.mapper;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** MyBatis DAO for knowledge-base documents and vector lifecycle metadata. */
@Mapper
public interface KnowledgeBaseMapper {
    /** Inserts or updates one knowledge-base document entity. */
    int upsert(KnowledgeBaseEntity entity);
    /** Persists one document and returns the supplied entity. */
    default KnowledgeBaseEntity save(KnowledgeBaseEntity entity) { upsert(entity); return entity; }
    /** Finds a document by its business identifier. */
    java.util.Optional<KnowledgeBaseEntity> findById(String id);
    /** Deletes the supplied document entity. */
    void delete(KnowledgeBaseEntity entity);
    /** Lists one owner's documents in newest-first order. */
    List<KnowledgeBaseEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    /** Lists one owner's documents in an exact category. */
    List<KnowledgeBaseEntity> findByOwnerIdAndCategory(@Param("ownerId") String ownerId, @Param("category") String category);
    /** Searches one owner's document names case-insensitively. */
    List<KnowledgeBaseEntity> findByOwnerIdAndNameContainingIgnoreCase(@Param("ownerId") String ownerId, @Param("keyword") String keyword);
    /** Lists one owner's documents in the requested vector lifecycle status. */
    List<KnowledgeBaseEntity> findByOwnerIdAndVectorStatus(@Param("ownerId") String ownerId, @Param("vectorStatus") String vectorStatus);
    /** Returns requested documents whose vector lifecycle is in the given state. */
    List<KnowledgeBaseEntity> findByIdInAndVectorStatus(@Param("ids") Iterable<String> ids, @Param("vectorStatus") String vectorStatus);
}
