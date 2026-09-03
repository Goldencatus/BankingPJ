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

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return errorResponse(exception.getErrorCode());
    }

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception
    ) {
        return errorResponse(ErrorCode.INVALID_REQUEST_FORMAT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return errorResponse(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static Stream<String> formatParameterValidationErrors(ParameterValidationResult result) {
        if (result instanceof ParameterErrors parameterErrors && parameterErrors.hasFieldErrors()) {
            return parameterErrors.getFieldErrors().stream()
                    .map(GlobalExceptionHandler::formatFieldError);
        }

        String parameterName = parameterName(result.getMethodParameter());
        return result.getResolvableErrors().stream()
                .map(error -> formatValidationError(parameterName, error));
    }

    private static String formatFieldError(FieldError error) {
        return formatValidationError(error.getField(), error);
    }

    private static String formatConstraintViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    private static String formatValidationError(String field, MessageSourceResolvable error) {
        String message = Objects.requireNonNullElse(
                error.getDefaultMessage(),
                ErrorCode.INVALID_INPUT_VALUE.getMessage()
        );
        return field + ": " + message;
    }

    private static String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name != null ? name : "argument[" + parameter.getParameterIndex() + "]";
    }

    private ResponseEntity<ApiResponse<Void>> validationErrorResponse(List<String> validationErrors) {
        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
        String message = validationErrors.isEmpty()
                ? errorCode.getMessage()
                : errorCode.getMessage() + " (" + String.join(", ", validationErrors) + ")";

        return errorResponse(errorCode, message);
    }

    private ResponseEntity<ApiResponse<Void>> errorResponse(ErrorCode errorCode) {
        return errorResponse(errorCode, errorCode.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> errorResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode.getCode(), message));
    }
}
