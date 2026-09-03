package com.bankingpj.backend.auth.token;

import com.bankingpj.backend.auth.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieFactory {

    private final AuthProperties properties;

    // 환경별 전송 정책과 refresh 수명을 받는다.
    public RefreshTokenCookieFactory(AuthProperties properties) {
        this.properties = properties;
    }

    // 인증 API와 구성된 토큰 수명으로 HttpOnly 쿠키를 제한한다.
    public ResponseCookie create(String token) {
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .path("/api/auth")
                .sameSite("Strict")
                .maxAge(properties.refreshTtlSeconds())
                .build();
    }
}
