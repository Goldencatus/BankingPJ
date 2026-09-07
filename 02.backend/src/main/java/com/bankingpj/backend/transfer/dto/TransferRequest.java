package com.bankingpj.backend.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull(message = "출금 계좌 ID는 필수입니다")
        @Positive(message = "출금 계좌 ID는 양수여야 합니다")
        Long fromAccountId,

        @NotBlank(message = "입금 계좌번호는 필수입니다")
        @Size(max = 30, message = "입금 계좌번호는 30자 이하여야 합니다")
        String toAccountNumber,

        @NotNull(message = "이체 금액은 필수입니다")
        @DecimalMin(value = "0.0001", message = "이체 금액은 0보다 커야 합니다")
        @Digits(integer = 15, fraction = 4, message = "이체 금액은 DECIMAL(19,4) 범위여야 합니다")
        BigDecimal amount
) {
}
