package com.bankingpj.backend.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WithdrawalResponse(
        Long accountId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        LocalDateTime createdAt
) {
}
