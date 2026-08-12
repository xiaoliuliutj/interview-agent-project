package com.interview.agent.upper.api.dto;

/** Failure envelope shared by every public controller. */
public record ApiErrorResponse(
        int code,
        String message,
        Object data,
        ApiErrorDetail error) {

    public static ApiErrorResponse of(ApiErrorDetail error) {
        return new ApiErrorResponse(error.httpStatus(), error.message(), null, error);
    }
}
