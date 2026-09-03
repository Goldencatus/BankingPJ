package com.bankingpj.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(String accessSecret, long accessTtlSeconds, long refreshTtlSeconds, boolean cookieSecure) {

    // Reject non-positive token lifetimes before accepting login requests.
    public AuthProperties {
        if (accessTtlSeconds <= 0 || refreshTtlSeconds <= 0) {
            throw new IllegalArgumentException("Authentication token TTLs must be positive");
        }
    }

    // Never include the signing secret in diagnostic string output.
    @Override
    public String toString() {
        return "AuthProperties[redacted]";
    }
}
