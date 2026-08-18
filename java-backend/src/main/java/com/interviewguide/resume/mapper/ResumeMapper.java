package com.interviewguide.resume.mapper;

import com.interviewguide.resume.domain.ResumeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Optional;
import java.util.List;

/** MyBatis DAO for versioned resume records and their stored-file metadata. */
@Mapper
public interface ResumeMapper {
    /** Inserts or updates one resume entity. */
    int upsert(ResumeEntity entity);
    /** Persists one resume and returns the supplied entity. */
    default ResumeEntity save(ResumeEntity entity) { upsert(entity); return entity; }
    /** Finds a resume by its business identifier. */
    Optional<ResumeEntity> findById(String id);
    /** Lists all durable resume rows for service-side ownership filtering. */
    List<ResumeEntity> findAll();
    /** Deletes the supplied resume entity. */
    void delete(ResumeEntity entity);
    /** Finds a duplicate file for one candidate by its content hash. */
    Optional<ResumeEntity> findFirstByCandidateIdAndFileHash(@Param("candidateId") String candidateId, @Param("fileHash") String fileHash);
    /** Lists every version belonging to one candidate. */
    List<ResumeEntity> findByCandidateId(String candidateId);
    /** Finds a candidate's newest resume version. */
    Optional<ResumeEntity> findFirstByCandidateIdOrderByVersionDesc(String candidateId);
}
