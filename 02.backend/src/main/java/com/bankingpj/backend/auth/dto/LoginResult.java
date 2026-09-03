package com.bankingpj.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record LoginResult(LoginResponse response, @JsonIgnore String refreshToken) {

    // The internal result carries a cookie token, which must not appear in logs.
    @Override
    public String toString() {
        return "LoginResult[redacted]";
    }
}
