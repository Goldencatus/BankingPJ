package com.bankingpj.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    // JPA가 저장된 회원을 복원할 때 사용하는 생성자다.
    protected User() {
    }

    // 비밀번호 해시와 가입 정보를 받아 기본 USER 역할의 회원을 생성한다.
    public User(String email, String passwordHash, String name, UserStatus status) {
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status);
    }

    // 최초 저장 시 회원 생성·수정 시각을 설정한다.
    @PrePersist
    private void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    // 회원 변경 내용을 저장할 때 수정 시각을 갱신한다.
    @PreUpdate
    private void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    // 회원 식별자를 반환한다.
    public Long getUserId() {
        return userId;
    }

    // 회원 이메일을 반환한다.
    public String getEmail() {
        return email;
    }

    // 비밀번호 검증에 사용할 저장된 해시를 반환한다.
    public String getPasswordHash() {
        return passwordHash;
    }

    // 회원 이름을 반환한다.
    public String getName() {
        return name;
    }

    // 회원 계정 상태를 반환한다.
    public UserStatus getStatus() {
        return status;
    }

    // 회원 권한 역할을 반환한다.
    public UserRole getRole() {
        return role;
    }

    // 회원 생성 시각을 반환한다.
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 회원의 마지막 수정 시각을 반환한다.
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
