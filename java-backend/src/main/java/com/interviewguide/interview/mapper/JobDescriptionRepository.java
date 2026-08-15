package com.interviewguide.interview.mapper;

import com.interviewguide.interview.domain.JobDescriptionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobDescriptionRepository {
    int upsert(JobDescriptionEntity entity);
    default JobDescriptionEntity save(JobDescriptionEntity entity) { upsert(entity); return entity; }
    java.util.Optional<JobDescriptionEntity> findById(String id);
}
