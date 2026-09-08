package com.bankingpj.backend.transfer.service;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.transfer.dto.TransferResponse;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class IdempotentTransferService {

    private static final int MAX_KEY_LENGTH = 100;

    private final UserRepository users;
    private final TransferRequestFingerprint fingerprints;
    private final TransferIdempotencyTransactionService transactions;

    // 회원 검증·요청 지문·트랜잭션 실행을 조정할 구성요소를 주입받는다.
    public IdempotentTransferService(UserRepository users, TransferRequestFingerprint fingerprints,
                                     TransferIdempotencyTransactionService transactions) {
        this.users = users;
        this.fingerprints = fingerprints;
        this.transactions = transactions;
    }

    // 기존 완료 결과를 재사용하고, 신규 키는 UNIQUE 경쟁을 거쳐 정확히 한 번만 이체한다.
    public TransferResponse transfer(Long userId, String idempotencyKey, Long fromAccountId,
                                     String toAccountNumber, BigDecimal amount) {
        validateActiveUser(userId);
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprints.create(userId, fromAccountId, toAccountNumber, amount);

        var replay = transactions.replay(userId, idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return transactions.executeNew(userId, idempotencyKey, fingerprint,
                    fromAccountId, toAccountNumber, amount);
        } catch (DataIntegrityViolationException exception) {
            return transactions.replay(userId, idempotencyKey, fingerprint)
                    .orElseThrow(() -> exception);
        }
    }

    // 인증 주체가 실제 ACTIVE 회원인지 금융 처리 전에 확인한다.
    private void validateActiveUser(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOGIN_NOT_ALLOWED);
        }
    }

    // 누락·공백·DB 허용 길이를 넘는 멱등성 키를 공통 입력 오류로 거부한다.
    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
