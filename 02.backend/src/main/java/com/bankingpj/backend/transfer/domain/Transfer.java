package com.bankingpj.backend.transfer.domain;

import com.bankingpj.backend.account.domain.Account;
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
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime completedAt;

    // JPA가 저장된 이체를 복원할 때 사용한다.
    protected Transfer() {
    }

    // 출금·입금 계좌와 양수 금액으로 REQUESTED 상태의 이체를 생성한다.
    public Transfer(Account fromAccount, Account toAccount, BigDecimal amount) {
        this.fromAccount = Objects.requireNonNull(fromAccount);
        this.toAccount = Objects.requireNonNull(toAccount);
        this.amount = Objects.requireNonNull(amount);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        status = TransferStatus.REQUESTED;
    }

    // 최초 저장 시 이체 요청 시각을 기록한다.
    @PrePersist
    private void initializeCreatedAt() {
        createdAt = LocalDateTime.now();
    }

    // REQUESTED 이체를 잔액 이동이 진행 중인 상태로 전환한다.
    public void startProcessing() {
        if (status != TransferStatus.REQUESTED) {
            throw new IllegalStateException("Only requested transfer can start processing");
        }
        status = TransferStatus.PROCESSING;
    }

    // PROCESSING 이체를 완료하고 완료 시각을 기록한다.
    public void complete() {
        if (status != TransferStatus.PROCESSING) {
            throw new IllegalStateException("Only processing transfer can complete");
        }
        status = TransferStatus.COMPLETED;
        completedAt = LocalDateTime.now();
    }

    // 이체 식별자를 반환한다.
    public Long getTransferId() {
        return transferId;
    }

    // 출금 계좌를 반환한다.
    public Account getFromAccount() {
        return fromAccount;
    }

    // 입금 계좌를 반환한다.
    public Account getToAccount() {
        return toAccount;
    }

    // 이체 요청 금액을 반환한다.
    public BigDecimal getAmount() {
        return amount;
    }

    // 현재 이체 상태를 반환한다.
    public TransferStatus getStatus() {
        return status;
    }

    // 이체 요청 생성 시각을 반환한다.
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 완료된 이체의 완료 시각을 반환한다.
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}
