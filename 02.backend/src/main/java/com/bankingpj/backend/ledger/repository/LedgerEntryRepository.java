package com.bankingpj.backend.ledger.repository;

import com.bankingpj.backend.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    // 계좌의 원장 항목을 생성 순서대로 조회한다.
    List<LedgerEntry> findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(Long accountId);
}
