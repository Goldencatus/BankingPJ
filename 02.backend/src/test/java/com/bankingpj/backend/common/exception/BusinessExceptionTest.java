package com.bankingpj.backend.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessExceptionTest {

    // 업무 예외가 오류 코드와 기본 메시지를 유지하는지 검증한다.
    @Test
    void keepsErrorCodeAndUsesItsMessage() {
        BusinessException exception = new BusinessException(ErrorCode.INVALID_INPUT_VALUE);

        assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.getErrorCode());
        assertEquals(ErrorCode.INVALID_INPUT_VALUE.getMessage(), exception.getMessage());
    }

    // 오류 코드 없는 업무 예외 생성을 거부하는지 검증한다.
    @Test
    void requiresErrorCode() {
        assertThrows(NullPointerException.class, () -> new BusinessException(null));
    }
}
