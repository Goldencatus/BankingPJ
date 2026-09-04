package com.bankingpj.backend.account.dto;

import com.bankingpj.backend.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountCreateResponse(
        Long accountId,
        String accountNumber,
        BigDecimal balance,
        AccountStatus status,
        LocalDateTime createdAt
) {
}
