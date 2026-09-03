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

    // Allow JPA to restore persisted refresh-token records.
    protected RefreshToken() {
    }

    // Store only a token hash and UTC timestamps derived from the issuance time.
    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.user = Objects.requireNonNull(user);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    // Return the persisted token record identifier.
    public Long getRefreshTokenId() { return refreshTokenId; }

    // Return the owning user through the lazy association.
    public User getUser() { return user; }

    // Return the one-way token hash used for database lookup.
    public String getTokenHash() { return tokenHash; }

    // Return the token expiration timestamp in UTC.
    public LocalDateTime getExpiresAt() { return expiresAt; }

    // Return the revocation timestamp, or null for a token not revoked.
    public LocalDateTime getRevokedAt() { return revokedAt; }

    // Return the token issuance timestamp in UTC.
    public LocalDateTime getCreatedAt() { return createdAt; }
}
