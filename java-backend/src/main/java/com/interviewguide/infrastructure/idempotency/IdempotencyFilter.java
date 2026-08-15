package com.interviewguide.infrastructure.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.common.web.dto.ApiErrorDetail;
import com.interviewguide.common.web.dto.ApiErrorResponse;
import com.interviewguide.infrastructure.redis.JavaRedisStore;
import com.interviewguide.infrastructure.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards opt-in mutable HTTP requests using X-Idempotency-Key. Redis is the
 * cross-instance guard; local state is used only when Java Redis is down.
 * Durable unique constraints/version checks remain the final guarantee.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Duration TTL = Duration.ofHours(24);
    private final JavaRedisStore store;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Long> fallback = new ConcurrentHashMap<>();

    public IdempotencyFilter(JavaRedisStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())
                || "PATCH".equals(request.getMethod()) || "DELETE".equals(request.getMethod()))
                || request.getHeader("X-Idempotency-Key") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Idempotency-Key").strip();
        if (supplied.isEmpty() || supplied.length() > 200) {
            writeConflict(request, response, "IDEMPOTENCY_KEY_INVALID", "X-Idempotency-Key is invalid");
            return;
        }
        String userId = request.getHeader("X-User-Id");
        String key = "java:idempotency:" + (userId == null ? "anonymous" : userId.strip())
                + ":" + request.getMethod() + ":" + request.getRequestURI() + ":" + supplied;
        Optional<Boolean> acquired = store.acquire(key, "PROCESSING", TTL);
        boolean accepted;
        if (acquired.isPresent()) {
            accepted = acquired.get();
        } else {
            long expiresAt = System.currentTimeMillis() + TTL.toMillis();
            accepted = fallback.putIfAbsent(key, expiresAt) == null;
            fallback.entrySet().removeIf(entry -> entry.getValue() < System.currentTimeMillis());
        }
        if (!accepted) {
            writeConflict(request, response, "IDEMPOTENT_REQUEST_DUPLICATE",
                    "the request has already been accepted; query task/session state instead");
            return;
        }
        try {
            filterChain.doFilter(request, response);
            // Client errors must remain retryable with the same key. Successful
            // or server-error writes stay guarded for the TTL; database rules
            // protect the rare Redis-expiry/retry race.
            if (response.getStatus() >= 400 && response.getStatus() < 500) {
                store.delete(key);
                fallback.remove(key);
            }
        } catch (IOException | ServletException | RuntimeException error) {
            store.delete(key);
            fallback.remove(key);
            throw error;
        }
    }

    private void writeConflict(HttpServletRequest request, HttpServletResponse response,
                               String code, String message) throws IOException {
        response.setStatus(409);
        response.setContentType("application/json;charset=UTF-8");
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = value instanceof String text ? text : null;
        ApiErrorDetail detail = new ApiErrorDetail(code, message, false, 409, requestId, null, null, "IDEMPOTENCY");
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(detail));
    }
}
