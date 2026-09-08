package com.bankingpj.backend.ledger.dto;

import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(Long ledgerEntryId, Long transferId, LedgerEntryType type,
                                  BigDecimal amount, BigDecimal balanceAfter, LocalDateTime createdAt) {
}
