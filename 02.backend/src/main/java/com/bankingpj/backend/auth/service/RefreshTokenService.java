package com.bankingpj.backend.auth.service;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.domain.RefreshToken;
import com.bankingpj.backend.auth.dto.LoginResponse;
import com.bankingpj.backend.auth.dto.LoginResult;
import com.bankingpj.backend.auth.repository.RefreshTokenRepository;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.auth.token.RefreshTokenGenerator;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository tokens;
    private final RefreshTokenGenerator generator;
    private final AccessTokenIssuer accessTokens;
    private final AuthProperties properties;
    private final Clock clock;

    // Rotation에 필요한 저장소, 토큰 발급기와 시간 설정을 주입받는다.
    public RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenGenerator generator,
                               AccessTokenIssuer accessTokens, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.generator = generator;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
    }

    // 유효한 Refresh Token을 잠근 뒤 폐기하고 새 Access·Refresh Token으로 교체한다.
    @Transactional
    public LoginResult refresh(String rawToken) {
        RefreshToken current = findForUpdate(rawToken);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime now = LocalDateTime.ofInstant(issuedAt, ZoneOffset.UTC);
        User user = current.getUser();
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)
                || user.getStatus() != UserStatus.ACTIVE) {
            throw invalidRefreshToken();
        }

        current.revoke(now);
        String accessToken = accessTokens.issue(user, issuedAt);
        String newRawToken = generator.generate();
        tokens.save(new RefreshToken(user, generator.hash(newRawToken),
                now.plusSeconds(properties.refreshTtlSeconds()), now));
        return new LoginResult(new LoginResponse(accessToken, "Bearer", properties.accessTtlSeconds()), newRawToken);
    }

    // 쿠키와 일치하는 토큰이 있으면 잠근 뒤 폐기하며 없거나 이미 폐기됐어도 성공한다.
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHashForUpdate(generator.hash(rawToken))
                .ifPresent(token -> token.revoke(LocalDateTime.ofInstant(
                        clock.instant().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC)));
    }

    // 원문을 해시로 바꾸어 행 잠금 조회하고 외부에는 상세 원인 없이 동일 오류를 반환한다.
    private RefreshToken findForUpdate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidRefreshToken();
        }
        return tokens.findByTokenHashForUpdate(generator.hash(rawToken)).orElseThrow(this::invalidRefreshToken);
    }

    // 모든 Refresh 인증 실패에 사용할 공통 공개 예외를 생성한다.
    private BusinessException invalidRefreshToken() {
        return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
}
