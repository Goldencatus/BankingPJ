package com.bankingpj.backend.common.exception;

import com.bankingpj.backend.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // 업무 예외가 자신의 오류 코드에 따라 응답되는지 검증한다.
    @Test
    void handlesBusinessExceptionWithItsErrorCode() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        );

        assertErrorResponse(response, ErrorCode.INVALID_INPUT_VALUE);
    }

    // DTO 검증 오류 메시지에 필드 이름이 포함되는지 검증한다.
    @Test
    void includesFieldNameForRequestBodyValidationError() throws NoSuchMethodException {
        ValidationRequest target = new ValidationRequest("");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "request");
        bindingResult.addError(new FieldError("request", "email", "이메일 형식이 올바르지 않습니다"));
        Method method = ValidationTarget.class.getDeclaredMethod("accept", ValidationRequest.class);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0),
                bindingResult
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentNotValid(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiResponse<Void> body = requireBody(response);
        assertEquals("COMMON_001", body.getError().code());
        assertEquals(
                "잘못된 입력값 (email: 이메일 형식이 올바르지 않습니다)",
                body.getError().message()
        );
    }

    // JSON 파싱 실패 응답이 파서 내부 정보를 숨기는지 검증한다.
    @Test
    void handlesUnreadableJsonWithoutExposingParserDetails() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Internal JSON parser details",
                new MockHttpInputMessage(new byte[0])
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpMessageNotReadable(exception);

        assertErrorResponse(response, ErrorCode.INVALID_REQUEST_FORMAT);
    }

    // 내부 예외 메시지가 공통 오류 응답에 노출되지 않는지 검증한다.
    @Test
    void handlesUnexpectedExceptionWithoutExposingInternalDetails() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("Sensitive internal details")
        );

        assertErrorResponse(response, ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // 공통 실패 응답의 상태·데이터·오류 코드·메시지를 확인한다.
    private void assertErrorResponse(
            ResponseEntity<ApiResponse<Void>> response,
            ErrorCode expectedErrorCode
    ) {
        assertEquals(expectedErrorCode.getHttpStatus(), response.getStatusCode());
        ApiResponse<Void> body = requireBody(response);
        assertFalse(body.isSuccess());
        assertNull(body.getData());
        assertNotNull(body.getError());
        assertEquals(expectedErrorCode.getCode(), body.getError().code());
        assertEquals(expectedErrorCode.getMessage(), body.getError().message());
    }

    // 검증할 HTTP 응답 본문이 존재하는지 확인하고 반환한다.
    private ApiResponse<Void> requireBody(ResponseEntity<ApiResponse<Void>> response) {
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private record ValidationRequest(String email) {
    }

    private static class ValidationTarget {

        // 요청 DTO 검증 예외를 구성할 리플렉션 대상 메서드를 제공한다.
        @SuppressWarnings("unused")
        void accept(ValidationRequest request) {
        }
    }
}
