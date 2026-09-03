package com.bankingpj.backend.common.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
