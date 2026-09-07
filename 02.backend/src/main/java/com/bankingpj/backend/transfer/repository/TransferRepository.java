package com.bankingpj.backend.transfer.repository;

import com.bankingpj.backend.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // 출금 계좌에서 생성된 이체를 생성 순서대로 조회한다.
    List<Transfer> findAllByFromAccount_AccountIdOrderByTransferIdAsc(Long accountId);
}
