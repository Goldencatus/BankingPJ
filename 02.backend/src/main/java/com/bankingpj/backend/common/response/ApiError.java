package com.bankingpj.backend.common.response;

import java.util.Objects;

public record ApiError(String code, String message) {

    // 공통 오류의 코드와 메시지가 누락되지 않았는지 확인한다.
    public ApiError {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
