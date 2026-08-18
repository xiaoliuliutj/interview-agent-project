package com.interviewguide.common.web;

/** Standard successful API envelope returned by every controller endpoint. */
public record ApiResult<T>(int code, String message, T data) {
    /** Creates the uniform HTTP-success payload while preserving the endpoint data type. */
    public static <T> ApiResult<T> success(T data) {
        // Keep the public success code and message consistent across all endpoints.
        return new ApiResult<>(200, "success", data);
    }
}
