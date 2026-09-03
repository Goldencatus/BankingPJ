package com.bankingpj.backend.user;

import com.bankingpj.backend.user.dto.SignupRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    // 요청 DTO 검증에 사용할 Bean Validation 검증기를 준비한다.
    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // 검증기 팩토리의 테스트 리소스를 해제한다.
    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // 필수값·형식·길이 위반 입력을 검증기가 거부하는지 확인한다.
    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsMissingMalformedOrOverlongValues(SignupRequest request) {
        assertThat(validator.validate(request).isEmpty()).isFalse();
    }

    // 회원가입 입력 제약을 위반하는 테스트 사례를 제공한다.
    static Stream<SignupRequest> invalidRequests() {
        return Stream.of(
                new SignupRequest(null, "password", "User"),
                new SignupRequest(" ", "password", "User"),
                new SignupRequest("invalid", "password", "User"),
                new SignupRequest("a".repeat(256) + "@example.com", "password", "User"),
                new SignupRequest("user@example.com", null, "User"),
                new SignupRequest("user@example.com", "        ", "User"),
                new SignupRequest("user@example.com", "short", "User"),
                new SignupRequest("user@example.com", "a".repeat(73), "User"),
                new SignupRequest("user@example.com", "가".repeat(25), "User"),
                new SignupRequest("user@example.com", "password", null),
                new SignupRequest("user@example.com", "password", " "),
                new SignupRequest("user@example.com", "password", "a".repeat(101))
        );
    }

    // 비밀번호 최소 길이와 ASCII·한글 바이트 경계의 유효 입력을 검증한다.
    @Test
    void acceptsMinimumLengthAndAsciiAndMultibyteByteBoundaries() {
        for (String password : new String[]{"a".repeat(8), "a".repeat(72), "가".repeat(24)}) {
            assertThat(validator.validate(new SignupRequest("user@example.com", password, "가".repeat(100))))
                    .isEmpty();
        }
    }

    // 가입 요청의 진단용 문자열에 비밀번호가 노출되지 않는지 검증한다.
    @Test
    void requestToStringDoesNotExposePassword() {
        assertThat(new SignupRequest("user@example.com", "sensitive-password", "User").toString())
                .doesNotContain("sensitive-password");
    }
}
