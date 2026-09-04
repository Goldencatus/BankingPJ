package com.bankingpj.backend.auth.domain;

import com.bankingpj.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refresh_token_id", nullable = false)
    private Long refreshTokenId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    // JPA가 저장된 Refresh Token을 복원할 때 사용한다.
    protected RefreshToken() {
    }

    // 원문 대신 토큰 해시와 UTC 기준 발급·만료 시각을 저장한다.
    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.user = Objects.requireNonNull(user);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    // 저장된 Refresh Token 식별자를 반환한다.
    public Long getRefreshTokenId() { return refreshTokenId; }

    // 토큰을 소유한 회원을 반환한다.
    public User getUser() { return user; }

    // DB 조회에 사용하는 단방향 토큰 해시를 반환한다.
    public String getTokenHash() { return tokenHash; }

    // 토큰 만료 시각을 반환한다.
    public LocalDateTime getExpiresAt() { return expiresAt; }

    // 폐기 시각을 반환하며 사용 가능한 토큰이면 null이다.
    public LocalDateTime getRevokedAt() { return revokedAt; }

    // 토큰 발급 시각을 반환한다.
    public LocalDateTime getCreatedAt() { return createdAt; }

    // 처음 폐기할 때만 시각을 기록하여 반복 로그아웃을 멱등적으로 처리한다.
    public void revoke(LocalDateTime revokedAt) {
        if (this.revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(revokedAt);
        }
    }
}
