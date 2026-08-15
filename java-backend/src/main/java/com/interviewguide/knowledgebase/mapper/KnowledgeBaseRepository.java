package com.interviewguide.knowledgebase.mapper;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeBaseRepository {
    int upsert(KnowledgeBaseEntity entity);
    default KnowledgeBaseEntity save(KnowledgeBaseEntity entity) { upsert(entity); return entity; }
    java.util.Optional<KnowledgeBaseEntity> findById(String id);
    void delete(KnowledgeBaseEntity entity);
    List<KnowledgeBaseEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<KnowledgeBaseEntity> findByOwnerIdAndCategory(@Param("ownerId") String ownerId, @Param("category") String category);
    List<KnowledgeBaseEntity> findByOwnerIdAndNameContainingIgnoreCase(@Param("ownerId") String ownerId, @Param("keyword") String keyword);
    List<KnowledgeBaseEntity> findByOwnerIdAndVectorStatus(@Param("ownerId") String ownerId, @Param("vectorStatus") String vectorStatus);
    List<KnowledgeBaseEntity> findByIdInAndVectorStatus(@Param("ids") Iterable<String> ids, @Param("vectorStatus") String vectorStatus);
}
