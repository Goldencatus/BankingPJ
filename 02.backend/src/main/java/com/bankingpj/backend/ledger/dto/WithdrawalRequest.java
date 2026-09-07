package com.bankingpj.backend.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawalRequest(
        @NotNull(message = "출금액은 필수입니다")
        @DecimalMin(value = "0.0001", message = "출금액은 0보다 커야 합니다")
        @Digits(integer = 15, fraction = 4, message = "출금액은 DECIMAL(19,4) 범위여야 합니다")
        BigDecimal amount
) {
}
