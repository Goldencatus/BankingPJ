package com.bankingpj.backend.common.response;

import java.util.Objects;

public final class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ApiError error;

    // 공통 응답의 성공 여부와 데이터·오류를 초기화한다.
    private ApiResponse(boolean success, T data, ApiError error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    // 데이터를 포함하는 성공 응답을 생성한다.
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    // 반환 데이터가 없는 성공 응답을 생성한다.
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null);
    }

    // 오류 코드와 메시지로 실패 응답을 생성한다.
    public static <T> ApiResponse<T> failure(String code, String message) {
        return failure(new ApiError(code, message));
    }

    // 공통 오류 객체로 데이터가 없는 실패 응답을 생성한다.
    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, Objects.requireNonNull(error, "error must not be null"));
    }

    // 요청 처리 성공 여부를 반환한다.
    public boolean isSuccess() {
        return success;
    }

    // 응답에 담긴 데이터를 반환한다.
    public T getData() {
        return data;
    }

    // 실패 응답의 오류 정보를 반환한다.
    public ApiError getError() {
        return error;
    }
}
