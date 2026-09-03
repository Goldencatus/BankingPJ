package com.bankingpj.backend.auth.service;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.domain.RefreshToken;
import com.bankingpj.backend.auth.dto.LoginRequest;
import com.bankingpj.backend.auth.dto.LoginResponse;
import com.bankingpj.backend.auth.dto.LoginResult;
import com.bankingpj.backend.auth.repository.RefreshTokenRepository;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.auth.token.RefreshTokenGenerator;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class LoginService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenGenerator refreshTokens;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    // 더미 계정 검증을 위해 존재하지 않는 계정에 대한 더미 해시를 준비한다.
    public LoginService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens,
                        RefreshTokenGenerator refreshTokens, RefreshTokenRepository refreshTokenRepository,
                        AuthProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // 검증된 자격 증명과 상태를 확인하고, refresh token의 hash값을 저장한 후 발급된 토큰을 반환한다.
    @Transactional
    public LoginResult login(LoginRequest request) {
        User user = users.findByEmail(request.email()).orElse(null);
        String passwordHash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(request.password(), passwordHash);
        if (user == null || !matches) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOGIN_NOT_ALLOWED);
        }

        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String accessToken = accessTokens.issue(user, issuedAt);
        String refreshToken = refreshTokens.generate();
        LocalDateTime createdAt = LocalDateTime.ofInstant(issuedAt, ZoneOffset.UTC);
        refreshTokenRepository.save(new RefreshToken(user, refreshTokens.hash(refreshToken),
                createdAt.plusSeconds(properties.refreshTtlSeconds()), createdAt));
        return new LoginResult(new LoginResponse(accessToken, "Bearer", properties.accessTtlSeconds()), refreshToken);
    }
}
