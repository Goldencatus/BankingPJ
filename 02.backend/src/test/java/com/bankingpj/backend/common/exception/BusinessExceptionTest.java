package com.bankingpj.backend.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessExceptionTest {

    @Test
    void keepsErrorCodeAndUsesItsMessage() {
        BusinessException exception = new BusinessException(ErrorCode.INVALID_INPUT_VALUE);

        assertEquals(ErrorCode.INVALID_INPUT_VALUE, exception.getErrorCode());
        assertEquals(ErrorCode.INVALID_INPUT_VALUE.getMessage(), exception.getMessage());
    }

    @Test
    void requiresErrorCode() {
        assertThrows(NullPointerException.class, () -> new BusinessException(null));
    }
}
