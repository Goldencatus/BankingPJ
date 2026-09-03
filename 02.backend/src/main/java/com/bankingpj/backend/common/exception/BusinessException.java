package com.bankingpj.backend.common.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    // 오류 코드와 해당 공개 메시지를 가진 업무 예외를 생성한다.
    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
    }

    // 업무 예외에 지정된 오류 코드를 반환한다.
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
