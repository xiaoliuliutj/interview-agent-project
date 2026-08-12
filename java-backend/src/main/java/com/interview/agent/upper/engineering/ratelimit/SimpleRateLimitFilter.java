package com.interview.agent.upper.engineering.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.upper.api.dto.ApiErrorDetail;
import com.interview.agent.upper.api.dto.ApiErrorResponse;
import com.interview.agent.upper.engineering.web.RequestIdFilter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 演示级固定窗口限流；鉴权留给后续版本。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SimpleRateLimitFilter extends OncePerRequestFilter {
    private record Window(long epochMinute, AtomicInteger count) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final ObjectMapper objectMapper;

    public SimpleRateLimitFilter(@Value("${agent.rate-limit.requests-per-minute:60}") int limit,
                                 ObjectMapper objectMapper) {
        this.limit = Math.max(1, limit);
        this.objectMapper = objectMapper;
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
        Window window = windows.compute(key, (ignored, old) ->
                old == null || old.epochMinute() != minute ? new Window(minute, new AtomicInteger()) : old);
        if (window.count().incrementAndGet() > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            String requestId = value instanceof String text ? text : null;
            ApiErrorDetail detail = new ApiErrorDetail(
                    "RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后重试", true, 429,
                    requestId, null, null, "RATE_LIMIT");
            objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(detail));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
