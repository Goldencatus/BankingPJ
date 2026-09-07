package com.bankingpj.backend.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DepositResponse(
        Long accountId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        LocalDateTime createdAt
) {
}
