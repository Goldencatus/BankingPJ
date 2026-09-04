package com.bankingpj.backend.common.exception;

import com.bankingpj.backend.common.response.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 메서드 인가 실패를 Security 필터의 전용 처리기로 전달한다.
    @ExceptionHandler(AccessDeniedException.class)
    public void propagateAccessDenied(AccessDeniedException exception) {
        throw exception;
    }

    // MVC 내부 인증 실패도 공통 AuthenticationEntryPoint에서 처리하도록 전달한다.
    @ExceptionHandler(AuthenticationException.class)
    public void propagateAuthenticationFailure(AuthenticationException exception) {
        throw exception;
    }

    // 업무 예외를 지정된 오류 코드의 공통 응답으로 변환한다.
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return errorResponse(exception.getErrorCode());
    }

    // 요청 DTO의 검증 실패를 필드별 메시지와 함께 반환한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        List<String> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .toList();

        return validationErrorResponse(fieldErrors);
    }

    // MVC 메서드 인자 검증 실패를 공통 검증 오류로 변환한다.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception
    ) {
        List<String> validationErrors = exception.getParameterValidationResults()
                .stream()
                .flatMap(GlobalExceptionHandler::formatParameterValidationErrors)
                .toList();

        return validationErrorResponse(validationErrors);
    }

    // 제약 위반의 속성 경로와 메시지를 검증 오류에 포함한다.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        List<String> validationErrors = exception.getConstraintViolations()
                .stream()
                .map(GlobalExceptionHandler::formatConstraintViolation)
                .toList();

        return validationErrorResponse(validationErrors);
    }

    // 파싱 내부 정보를 제외하고 요청 형식 오류를 반환한다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception
    ) {
        return errorResponse(ErrorCode.INVALID_REQUEST_FORMAT);
    }

    // 예상 밖 예외를 서버에 기록하고 공개용 내부 오류 응답을 반환한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return errorResponse(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // 객체 필드 또는 단일 인자 검증 결과를 메시지 목록으로 변환한다.
    private static Stream<String> formatParameterValidationErrors(ParameterValidationResult result) {
        if (result instanceof ParameterErrors parameterErrors && parameterErrors.hasFieldErrors()) {
            return parameterErrors.getFieldErrors().stream()
                    .map(GlobalExceptionHandler::formatFieldError);
        }

        String parameterName = parameterName(result.getMethodParameter());
        return result.getResolvableErrors().stream()
                .map(error -> formatValidationError(parameterName, error));
    }

    // 필드 이름과 검증 메시지를 결합한다.
    private static String formatFieldError(FieldError error) {
        return formatValidationError(error.getField(), error);
    }

    // 제약 위반의 속성 경로와 메시지를 결합한다.
    private static String formatConstraintViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    // 검증 대상 이름과 공개 메시지를 오류 설명으로 구성한다.
    private static String formatValidationError(String field, MessageSourceResolvable error) {
        String message = Objects.requireNonNullElse(
                error.getDefaultMessage(),
                ErrorCode.INVALID_INPUT_VALUE.getMessage()
        );
        return field + ": " + message;
    }

    // 인자 이름이 없으면 위치를 이용해 검증 대상 식별자를 만든다.
    private static String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name != null ? name : "argument[" + parameter.getParameterIndex() + "]";
    }

    // 검증 상세 내용을 COMMON_001 응답에 포함한다.
    private ResponseEntity<ApiResponse<Void>> validationErrorResponse(List<String> validationErrors) {
        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
        String message = validationErrors.isEmpty()
                ? errorCode.getMessage()
                : errorCode.getMessage() + " (" + String.join(", ", validationErrors) + ")";

        return errorResponse(errorCode, message);
    }

    // 오류 코드의 기본 메시지로 공통 오류 응답을 생성한다.
    private ResponseEntity<ApiResponse<Void>> errorResponse(ErrorCode errorCode) {
        return errorResponse(errorCode, errorCode.getMessage());
    }

    // 지정한 공개 메시지와 HTTP 상태로 공통 오류 응답을 생성한다.
    private ResponseEntity<ApiResponse<Void>> errorResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode.getCode(), message));
    }
}
