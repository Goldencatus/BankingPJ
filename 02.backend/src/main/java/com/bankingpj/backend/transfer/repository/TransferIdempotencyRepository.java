package com.bankingpj.backend.transfer.repository;

import com.bankingpj.backend.transfer.domain.TransferIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferIdempotencyRepository extends JpaRepository<TransferIdempotency, Long> {

    // 회원 범위의 멱등성 키로 기존 처리 정보를 조회한다.
    Optional<TransferIdempotency> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 테스트와 정합성 점검에 사용할 회원별 키의 행 수를 반환한다.
    long countByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
