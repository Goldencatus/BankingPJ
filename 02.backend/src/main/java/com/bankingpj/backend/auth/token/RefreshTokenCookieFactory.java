package com.bankingpj.backend.auth.token;

import com.bankingpj.backend.auth.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieFactory {

    public static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";
    private static final String SAME_SITE = "Strict";

    private final AuthProperties properties;

    // 환경별 전송 정책과 refresh 수명을 받는다.
    public RefreshTokenCookieFactory(AuthProperties properties) {
        this.properties = properties;
    }

    // 인증 API와 구성된 토큰 수명으로 HttpOnly 쿠키를 제한한다.
    public ResponseCookie create(String token) {
        return cookie(token)
                .maxAge(properties.refreshTtlSeconds())
                .build();
    }

    // 로그인과 같은 속성에 Max-Age 0을 지정하여 브라우저의 Refresh 쿠키를 제거한다.
    public ResponseCookie delete() {
        return cookie("")
                .maxAge(0)
                .build();
    }

    // 생성과 삭제에 동일한 보안 속성과 경로를 적용할 쿠키 빌더를 제공한다.
    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .path(COOKIE_PATH)
                .sameSite(SAME_SITE);
    }
}
