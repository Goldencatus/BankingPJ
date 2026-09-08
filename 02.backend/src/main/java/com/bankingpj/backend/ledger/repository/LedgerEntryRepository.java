package com.bankingpj.backend.ledger.repository;

import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.dto.TransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    // 필요한 원장 필드만 페이지로 조회하며 동일 시각에는 원장 ID로 최신순을 확정한다.
    @Query(value = """
            select new com.bankingpj.backend.ledger.dto.TransactionResponse(
                e.ledgerEntryId, t.transferId, e.type, e.amount, e.balanceAfter, e.createdAt)
            from LedgerEntry e left join e.transfer t
            where e.account.accountId = :accountId
            order by e.createdAt desc, e.ledgerEntryId desc
            """, countQuery = "select count(e) from LedgerEntry e where e.account.accountId = :accountId")
    Page<TransactionResponse> findTransactions(
            @Param("accountId") Long accountId,
            Pageable pageable);

    // 계좌의 원장 항목을 생성 순서대로 조회한다.
    List<LedgerEntry> findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(Long accountId);

    // 한 이체에서 생성된 DEBIT·CREDIT 원장을 생성 순서대로 조회한다.
    List<LedgerEntry> findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(Long transferId);
}
