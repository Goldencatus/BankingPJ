package com.bankingpj.backend.auth.token;

import com.bankingpj.backend.user.domain.UserRole;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;

public class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    // 발급 정책에 필요한 만료 시각, 양의 회원 ID, 허용된 역할이 있는지 검증한다.
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object role = jwt.getClaims().get("role");
        boolean allowedRole = role instanceof String
                && Arrays.stream(UserRole.values()).anyMatch(value -> value.name().equals(role));
        if (jwt.getExpiresAt() == null || !validUserId(jwt.getSubject()) || !allowedRole) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token claims", null));
        }
        return OAuth2TokenValidatorResult.success();
    }

    // 회원 ID가 DB 식별자 범위의 양의 십진수 문자열인지 확인한다.
    private boolean validUserId(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]*")) {
            return false;
        }
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
