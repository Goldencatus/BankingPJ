package com.bankingpj.backend.transfer.service;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class TransferRequestFingerprint {

    // 회원·출금계좌·수취계좌·정규화 금액으로 재현 가능한 SHA-256 지문을 생성한다.
    public String create(Long userId, Long fromAccountId, String toAccountNumber, BigDecimal amount) {
        BigDecimal normalizedAmount;
        try {
            normalizedAmount = amount.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String canonicalRequest = userId + "|" + fromAccountId + "|"
                + toAccountNumber.length() + ":" + toAccountNumber + "|" + normalizedAmount.toPlainString();
        return HexFormat.of().formatHex(sha256(canonicalRequest));
    }

    // JDK 기본 구현으로 canonical request의 SHA-256 바이트를 계산한다.
    private byte[] sha256(String canonicalRequest) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
