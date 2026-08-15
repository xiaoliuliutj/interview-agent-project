package com.interviewguide.common.web;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.common.web.dto.ApiErrorDetail;
import com.interviewguide.common.web.dto.ApiErrorResponse;
import com.interviewguide.infrastructure.web.RequestIdFilter;
import com.interviewguide.pythonagent.exception.PythonAgentException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/** Converts known failures into the shared API error envelope. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException error, HttpServletRequest request) {
        return response(error.httpStatus(), error.code(), error.getMessage(), error.retryable(),
                firstNonBlank(error.requestId(), requestId(request)), error.runId(), error.sessionId(), error.stage());
    }

    @ExceptionHandler(PythonAgentException.class)
    public ResponseEntity<ApiErrorResponse> handlePythonAgent(PythonAgentException error, HttpServletRequest request) {
        HttpStatus status = error.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        log.warn("Python service call failed: requestId={}, retryable={}", requestId(request), error.retryable(), error);
        return response(status, "PYTHON_SERVICE_UNAVAILABLE",
                error.retryable() ? "Python service is temporarily unavailable; please retry later"
                        : "Python service rejected the request",
                error.retryable(), requestId(request), null, null, "PYTHON_SERVICE_CALL");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException error, HttpServletRequest request) {
        String field = error.getBindingResult().getFieldErrors().stream()
                .findFirst().map(item -> item.getField()).orElse(null);
        String message = field == null ? "request validation failed" : "request validation failed: " + field;
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
                false, requestId(request), null, null, "REQUEST_VALIDATION");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed",
                false, requestId(request), null, null, "REQUEST_VALIDATION");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "request body is invalid JSON",
                false, requestId(request), null, null, "REQUEST_PARSING");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_HEADER_REQUIRED",
                "required request header is missing: " + error.getHeaderName(), false,
                requestId(request), null, null, "REQUEST_VALIDATION");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadLimit(MaxUploadSizeExceededException error, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "uploaded file exceeds the allowed size",
                false, requestId(request), null, null, "FILE_UPLOAD");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException error, HttpServletRequest request) {
        log.error("Data access failed: requestId={}", requestId(request), error);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATA_SERVICE_UNAVAILABLE", "data service is temporarily unavailable",
                true, requestId(request), null, null, "DATA_ACCESS");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "API_NOT_FOUND", "requested API was not found",
                false, requestId(request), null, null, "HTTP_ROUTING");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method is not supported",
                false, requestId(request), null, null, "HTTP_ROUTING");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException error, HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_NOT_SUPPORTED", "content type is not supported",
                false, requestId(request), null, null, "REQUEST_PARSING");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected API error: requestId={}", requestId(request), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "internal server error",
                true, requestId(request), null, null, "SERVER_PROCESSING");
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
