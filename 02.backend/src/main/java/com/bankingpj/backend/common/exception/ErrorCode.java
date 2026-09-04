package com.bankingpj.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_INPUT_VALUE("COMMON_001", HttpStatus.BAD_REQUEST, "잘못된 입력값"),
    INVALID_REQUEST_FORMAT("COMMON_002", HttpStatus.BAD_REQUEST, "잘못된 요청 형식"),
    INTERNAL_SERVER_ERROR("COMMON_999", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류"),
    DUPLICATE_EMAIL("USER_001", HttpStatus.CONFLICT, "이미 사용 중인 이메일"),
    INVALID_LOGIN_CREDENTIALS("AUTH_001", HttpStatus.UNAUTHORIZED, "잘못된 로그인 정보"),
    LOGIN_NOT_ALLOWED("AUTH_002", HttpStatus.FORBIDDEN, "로그인할 수 없는 사용자 상태"),
    INVALID_ACCESS_TOKEN("AUTH_003", HttpStatus.UNAUTHORIZED, "인증이 필요하거나 Access Token이 유효하지 않음"),
    ACCESS_DENIED("AUTH_004", HttpStatus.FORBIDDEN, "해당 작업에 대한 권한이 없음"),
    INVALID_REFRESH_TOKEN("AUTH_005", HttpStatus.UNAUTHORIZED, "Refresh Token이 없거나 유효하지 않음"),
    ACCOUNT_NOT_FOUND("ACCOUNT_001", HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;

    // 공개 오류 코드에 HTTP 상태와 메시지를 연결한다.
    ErrorCode(String code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    // 클라이언트에 전달할 오류 식별자를 반환한다.
    public String getCode() {
        return code;
    }

    // 오류 응답에 사용할 HTTP 상태를 반환한다.
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    // 클라이언트에 공개할 오류 메시지를 반환한다.
    public String getMessage() {
        return message;
    }
}
