package com.interview.agent.upper.engineering.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 演示级固定窗口限流；鉴权留给后续版本。 */
@Component
public class SimpleRateLimitFilter extends OncePerRequestFilter {
    private record Window(long epochMinute, AtomicInteger count) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;

    public SimpleRateLimitFilter(@Value("${agent.rate-limit.requests-per-minute:60}") int limit) {
        this.limit = Math.max(1, limit);
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
            response.getWriter().write("{\"code\":429,\"message\":\"rate limit exceeded\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
