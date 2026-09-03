package com.bankingpj.backend.auth.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    // Avoid accidentally logging the access token when inspecting this DTO.
    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }
}
