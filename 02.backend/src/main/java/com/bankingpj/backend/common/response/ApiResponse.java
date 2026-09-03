package com.bankingpj.backend.common.response;

import java.util.Objects;

public final class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ApiError error;

    private ApiResponse(boolean success, T data, ApiError error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return failure(new ApiError(code, message));
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, Objects.requireNonNull(error, "error must not be null"));
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public ApiError getError() {
        return error;
    }
}
