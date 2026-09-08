package com.bankingpj.backend.transfer.service;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.transfer.domain.Transfer;
import com.bankingpj.backend.transfer.domain.TransferIdempotency;
import com.bankingpj.backend.transfer.domain.TransferIdempotencyStatus;
import com.bankingpj.backend.transfer.dto.TransferResponse;
import com.bankingpj.backend.transfer.repository.TransferIdempotencyRepository;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class TransferIdempotencyTransactionService {

    private final TransferIdempotencyRepository idempotencies;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final TransferService transferService;

    // 멱등성 선점과 실제 이체를 같은 트랜잭션에서 처리할 구성요소를 주입받는다.
    public TransferIdempotencyTransactionService(TransferIdempotencyRepository idempotencies,
                                                 TransferRepository transfers,
                                                 LedgerEntryRepository ledgerEntries,
                                                 TransferService transferService) {
        this.idempotencies = idempotencies;
        this.transfers = transfers;
        this.ledgerEntries = ledgerEntries;
        this.transferService = transferService;
    }

    // 멱등성 키를 먼저 DB에 선점한 뒤 이체와 완료 연결을 하나의 트랜잭션으로 커밋한다.
    @Transactional
    public TransferResponse executeNew(Long userId, String idempotencyKey, String fingerprint,
                                       Long fromAccountId, String toAccountNumber, BigDecimal amount) {
        TransferIdempotency idempotency = idempotencies.saveAndFlush(
                new TransferIdempotency(userId, idempotencyKey, fingerprint));
        TransferResponse response = transferService.transfer(userId, fromAccountId, toAccountNumber, amount);
        Transfer transfer = transfers.findById(response.transferId())
                .orElseThrow(() -> new IllegalStateException("Completed transfer was not found"));
        idempotency.complete(transfer);
        idempotencies.saveAndFlush(idempotency);
        return response;
    }

    // 완료된 동일 요청을 찾으면 당시 Transfer와 DEBIT 원장으로 원래 응답을 복원한다.
    @Transactional(readOnly = true)
    public Optional<TransferResponse> replay(Long userId, String idempotencyKey, String fingerprint) {
        return idempotencies.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(idempotency -> replayCompleted(idempotency, fingerprint));
    }

    // 키의 요청 지문과 완료 상태를 확인하고 현재 잔액을 읽지 않은 응답을 만든다.
    private TransferResponse replayCompleted(TransferIdempotency idempotency, String fingerprint) {
        if (!idempotency.getRequestFingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (idempotency.getStatus() != TransferIdempotencyStatus.COMPLETED || idempotency.getTransfer() == null) {
            throw new IllegalStateException("Idempotency result is not completed");
        }
        Transfer transfer = idempotency.getTransfer();
        LedgerEntry debitEntry = ledgerEntries
                .findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(transfer.getTransferId())
                .stream()
                .filter(entry -> entry.getType() == LedgerEntryType.DEBIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Transfer debit ledger was not found"));
        return new TransferResponse(transfer.getTransferId(), transfer.getFromAccount().getAccountId(),
                transfer.getToAccount().getAccountNumber(), transfer.getAmount(), transfer.getStatus(),
                debitEntry.getBalanceAfter(), transfer.getCompletedAt());
    }
}
