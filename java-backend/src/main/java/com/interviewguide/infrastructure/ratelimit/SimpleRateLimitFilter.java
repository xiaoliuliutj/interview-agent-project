package com.interviewguide.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.common.web.dto.ApiErrorDetail;
import com.interviewguide.common.web.dto.ApiErrorResponse;
import com.interviewguide.infrastructure.web.RequestIdFilter;
import com.interviewguide.infrastructure.redis.JavaRedisStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 婕旂ず绾у浐瀹氱獥鍙ｉ檺娴侊紱閴存潈鐣欑粰鍚庣画鐗堟湰銆?*/
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SimpleRateLimitFilter extends OncePerRequestFilter {
    private record Window(long epochMinute, AtomicInteger count) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final ObjectMapper objectMapper;
    private final JavaRedisStore redisStore;

    /** Compatibility constructor for focused unit tests without Redis wiring. */
    public SimpleRateLimitFilter(int limit, ObjectMapper objectMapper) {
        this(limit, objectMapper, null);
    }

    public SimpleRateLimitFilter(@Value("${agent.rate-limit.requests-per-minute:60}") int limit,
                                 ObjectMapper objectMapper, JavaRedisStore redisStore) {
        this.limit = Math.max(1, limit);
        this.objectMapper = objectMapper;
        this.redisStore = redisStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().equals("/health") || request.getRequestURI().startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        long minute = Instant.now().getEpochSecond() / 60;
        Optional<Long> distributed = redisStore == null ? Optional.empty()
                : redisStore.incrementInFixedWindow(
                        "java:rate-limit:" + key + ":" + minute, Duration.ofSeconds(65));
        long count;
        if (distributed.isPresent()) {
            count = distributed.get();
        } else {
            // Redis failure must not turn into an availability failure; retain a
            // conservative in-process limiter until the Java Redis recovers.
            Window window = windows.compute(key, (ignored, old) ->
                    old == null || old.epochMinute() != minute ? new Window(minute, new AtomicInteger()) : old);
            count = window.count().incrementAndGet();
        }
        if (count > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            String requestId = value instanceof String text ? text : null;
            ApiErrorDetail detail = new ApiErrorDetail(
                    "RATE_LIMIT_EXCEEDED", "璇锋眰杩囦簬棰戠箒锛岃绋嶅悗閲嶈瘯", true, 429,
                    requestId, null, null, "RATE_LIMIT");
            objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(detail));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
