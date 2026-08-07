package com.interview.agent.upper.api.dto;

public record ApiResult<T>(int code, String message, T data) {
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(200, "success", data);
    }
}
