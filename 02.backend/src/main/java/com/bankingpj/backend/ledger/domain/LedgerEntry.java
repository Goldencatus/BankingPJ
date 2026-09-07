package com.bankingpj.backend.ledger.domain;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.transfer.domain.Transfer;
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
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_entry_id", nullable = false)
    private Long ledgerEntryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private LedgerEntryType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    // JPA가 저장된 원장 항목을 복원할 때 사용한다.
    protected LedgerEntry() {
    }

    // 계좌 변경 결과와 양수 CREDIT 또는 음수 DEBIT 금액을 원장 항목으로 생성한다.
    public LedgerEntry(Account account, LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter) {
        this(account, type, amount, balanceAfter, null);
    }

    // 이체 원장은 Transfer를 연결하고 단순 입출금 원장은 null 연결을 허용한다.
    public LedgerEntry(Account account, LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter,
                       Transfer transfer) {
        this.account = Objects.requireNonNull(account);
        this.type = Objects.requireNonNull(type);
        this.amount = Objects.requireNonNull(amount);
        this.balanceAfter = Objects.requireNonNull(balanceAfter);
        this.transfer = transfer;
        if ((type == LedgerEntryType.CREDIT && amount.signum() <= 0)
                || (type == LedgerEntryType.DEBIT && amount.signum() >= 0)) {
            throw new IllegalArgumentException("Ledger amount sign does not match type");
        }
    }

    // 최초 저장 시 원장 생성 시각을 기록한다.
    @PrePersist
    private void initializeCreatedAt() {
        createdAt = LocalDateTime.now();
    }

    // 원장 항목 식별자를 반환한다.
    public Long getLedgerEntryId() {
        return ledgerEntryId;
    }

    // 원장이 기록된 계좌를 반환한다.
    public Account getAccount() {
        return account;
    }

    // CREDIT 또는 DEBIT 원장 유형을 반환한다.
    public LedgerEntryType getType() {
        return type;
    }

    // CREDIT은 양수, DEBIT은 음수인 원장 금액을 반환한다.
    public BigDecimal getAmount() {
        return amount;
    }

    // 원장 반영 직후의 계좌 잔액을 반환한다.
    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    // 원장 항목 생성 시각을 반환한다.
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 이체 원장이 참조하는 Transfer를 반환하며 단순 입출금이면 null을 반환한다.
    public Transfer getTransfer() {
        return transfer;
    }
}
