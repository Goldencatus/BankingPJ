package com.bankingpj.backend.transfer.dto;

import com.bankingpj.backend.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
        Long transferId,
        Long fromAccountId,
        String toAccountNumber,
        BigDecimal amount,
        TransferStatus status,
        BigDecimal fromBalanceAfter,
        LocalDateTime completedAt
) {
}
