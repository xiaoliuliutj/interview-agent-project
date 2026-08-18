package com.interviewguide.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Best-effort Java-owned Redis access.  No business transaction relies on a
 * successful Redis call: callers can fall back to database or local state.
 */
@Component
public class JavaRedisStore {
    private static final Logger log = LoggerFactory.getLogger(JavaRedisStore.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public JavaRedisStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Optional<Long> incrementInFixedWindow(String key, Duration ttl) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, ttl);
            return Optional.ofNullable(count);
        } catch (DataAccessException error) {
            log.warn("Java Redis rate-limit increment failed; caller will use local fallback: key={}", key);
            return Optional.empty();
        }
    }

    public Optional<Boolean> acquire(String key, String value, Duration ttl) {
        try {
            return Optional.of(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl)));
        } catch (DataAccessException error) {
            log.warn("Java Redis idempotency acquire failed; caller will use local fallback: key={}", key);
            return Optional.empty();
        }
    }

    public void putJson(String key, Map<String, Object> value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (DataAccessException | JsonProcessingException error) {
            // Cache writes never invalidate an already committed business state.
            log.warn("Java Redis task-cache write failed; database state remains authoritative: key={}", key);
        }
    }

    public Optional<Map<String, Object>> getJson(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(raw, new TypeReference<>() { }));
        } catch (DataAccessException | JsonProcessingException error) {
            log.warn("Java Redis task-cache read failed; caller must query database: key={}", key);
            return Optional.empty();
        }
    }

    public void delete(String key) {
        try { redis.delete(key); }
        catch (DataAccessException error) { log.warn("Java Redis delete failed; entry will expire: key={}", key); }
    }
}
