package com.interview.agent.upper.api;

import com.interview.agent.upper.agent.AgentGatewayException;
import com.interview.agent.upper.api.dto.ApiErrorDetail;
import com.interview.agent.upper.api.dto.ApiErrorResponse;
import com.interview.agent.upper.engineering.web.RequestIdFilter;
import com.interview.agent.upper.service.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException error, HttpServletRequest request) {
        return response(error.httpStatus(), error.code(), error.getMessage(), error.retryable(),
                firstNonBlank(error.requestId(), requestId(request)), error.runId(), error.sessionId(), error.stage());
    }

    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<ApiErrorResponse> handleAgentGateway(
            AgentGatewayException error, HttpServletRequest request) {
        HttpStatus status = error.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        log.warn("Lower Agent gateway failed: requestId={}, retryable={}", requestId(request), error.retryable(), error);
        return response(status, "AGENT_GATEWAY_UNAVAILABLE",
                error.retryable() ? "AI 服务暂时不可用，请稍后重试" : "AI 服务返回了无效响应",
                error.retryable(), requestId(request), null, null, "AGENT_CALL");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException error, HttpServletRequest request) {
        String field = error.getBindingResult().getFieldErrors().stream()
                .findFirst().map(item -> item.getField()).orElse(null);
        String message = field == null ? "请求参数校验失败" : "请求参数不正确：" + field;
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
                false, requestId(request), null, null, "REQUEST_VALIDATION");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败",
                false, requestId(request), null, null, "REQUEST_VALIDATION");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "请求内容不是有效的 JSON",
                false, requestId(request), null, null, "REQUEST_PARSING");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_HEADER_REQUIRED",
                "缺少必要的请求头：" + error.getHeaderName(), false,
                requestId(request), null, null, "REQUEST_VALIDATION");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadLimit(
            MaxUploadSizeExceededException error, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "上传文件超过大小限制",
                false, requestId(request), null, null, "FILE_UPLOAD");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException error, HttpServletRequest request) {
        log.error("Data access failed: requestId={}", requestId(request), error);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATA_SERVICE_UNAVAILABLE",
                "数据服务暂时不可用，请稍后重试", true,
                requestId(request), null, null, "DATA_ACCESS");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoResourceFoundException error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "API_NOT_FOUND", "请求的接口不存在",
                false, requestId(request), null, null, "HTTP_ROUTING");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "该接口不支持当前请求方式",
                false, requestId(request), null, null, "HTTP_ROUTING");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaType(
            HttpMediaTypeNotSupportedException error, HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_NOT_SUPPORTED", "请求内容类型不受支持",
                false, requestId(request), null, null, "REQUEST_PARSING");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected API error: requestId={}", requestId(request), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "服务器处理失败，请稍后重试", true,
                requestId(request), null, null, "SERVER_PROCESSING");
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String type, String message, boolean retryable, String requestId,
            String runId, String sessionId, String stage) {
        ApiErrorDetail detail = new ApiErrorDetail(
                type, message, retryable, status.value(), requestId, runId, sessionId, stage);
        return ResponseEntity.status(status).body(ApiErrorResponse.of(detail));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (value instanceof String text && !text.isBlank()) return text;
        String header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
