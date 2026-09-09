package com.bankingpj.backend.account.dto;

import com.bankingpj.backend.account.domain.AccountStatus;

import java.time.LocalDateTime;

public record AccountStatusResponse(
        Long accountId,
        AccountStatus status,
        LocalDateTime updatedAt
) {
}
