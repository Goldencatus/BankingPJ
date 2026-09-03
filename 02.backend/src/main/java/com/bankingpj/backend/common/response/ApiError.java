package com.bankingpj.backend.common.response;

import java.util.Objects;

public record ApiError(String code, String message) {

    public ApiError {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
