package com.bankingpj.backend.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "transfer_idempotencies")
public class TransferIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "idempotency_id", nullable = false)
    private Long idempotencyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferIdempotencyStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", unique = true)
    private Transfer transfer;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "completed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime completedAt;

    // JPA가 저장된 이체 멱등성 정보를 복원할 때 사용한다.
    protected TransferIdempotency() {
    }

    // 회원별 키와 요청 지문을 PROCESSING 상태로 선점한다.
    public TransferIdempotency(Long userId, String idempotencyKey, String requestFingerprint) {
        this.userId = Objects.requireNonNull(userId);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint);
        status = TransferIdempotencyStatus.PROCESSING;
    }

    // 최초 저장 시 멱등성 키를 선점한 시각을 기록한다.
    @PrePersist
    private void initializeCreatedAt() {
        createdAt = LocalDateTime.now();
    }

    // 성공한 이체를 한 번만 연결하고 멱등성 처리를 완료한다.
    public void complete(Transfer completedTransfer) {
        if (status != TransferIdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("Only processing idempotency can be completed");
        }
        transfer = Objects.requireNonNull(completedTransfer);
        status = TransferIdempotencyStatus.COMPLETED;
        completedAt = LocalDateTime.now();
    }

    // 멱등성 행 식별자를 반환한다.
    public Long getIdempotencyId() {
        return idempotencyId;
    }

    // 멱등성 범위를 구성하는 회원 식별자를 반환한다.
    public Long getUserId() {
        return userId;
    }

    // 클라이언트가 전달한 멱등성 키를 반환한다.
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    // 정규화된 업무 요청의 SHA-256 지문을 반환한다.
    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    // 현재 멱등성 처리 상태를 반환한다.
    public TransferIdempotencyStatus getStatus() {
        return status;
    }

    // 완료된 요청에 연결된 이체를 반환한다.
    public Transfer getTransfer() {
        return transfer;
    }

    // 멱등성 키가 선점된 시각을 반환한다.
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 멱등성 처리가 완료된 시각을 반환한다.
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}
