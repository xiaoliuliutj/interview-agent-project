package com.interview.agent.upper.api;

import com.interview.agent.upper.service.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleBusiness(BusinessException error) {
        return new ApiError(error.code(), error.getMessage());
    }

    public record ApiError(String code, String message) {
    }
}
