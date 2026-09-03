package com.bankingpj.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_INPUT_VALUE("COMMON_001", HttpStatus.BAD_REQUEST, "잘못된 입력값"),
    INVALID_REQUEST_FORMAT("COMMON_002", HttpStatus.BAD_REQUEST, "잘못된 요청 형식"),
    INTERNAL_SERVER_ERROR("COMMON_999", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(String code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
