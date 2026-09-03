package com.bankingpj.backend.account.domain;
import com.bankingpj.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY,  optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    // JPA가 저장된 계좌를 복원할 때 사용하는 생성자다.
    protected Account() {
    }

    // 소유자와 계좌 개설 정보를 받아 계좌를 생성한다.
    public Account(User user, String accountNumber, BigDecimal balance, AccountStatus status) {
        this.user = Objects.requireNonNull(user);
        this.accountNumber = Objects.requireNonNull(accountNumber);
        this.balance = Objects.requireNonNull(balance);
        this.status = Objects.requireNonNull(status);
    }

    // 최초 저장 시 생성·수정 시각을 함께 설정한다.
    @PrePersist
    private void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    // 계좌 변경 내용을 저장할 때 수정 시각을 갱신한다.
    @PreUpdate
    private void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    // 계좌 식별자를 반환한다.
    public Long getAccountId() {
        return accountId;
    }

    // 계좌 소유자 연관 객체를 반환한다.
    public User getUser() {
        return user;
    }

    // 계좌번호를 반환한다.
    public String getAccountNumber() {
        return accountNumber;
    }

    // 계좌 잔액을 반환한다.
    public BigDecimal getBalance() {
        return balance;
    }

    // 계좌 상태를 반환한다.
    public AccountStatus getStatus() {
        return status;
    }

    // 계좌 생성 시각을 반환한다.
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 계좌의 마지막 수정 시각을 반환한다.
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
