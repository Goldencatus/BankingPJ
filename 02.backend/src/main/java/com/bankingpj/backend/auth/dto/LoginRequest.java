package com.bankingpj.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record LoginRequest(
        @NotBlank(message = "이메일은 필수입니다")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다")
        String email,
        @NotBlank(message = "비밀번호는 필수입니다")
        @Size(max = 72, message = "비밀번호는 72자 이하여야 합니다")
        String password
) {
    // 승인 전 BCrypt의 UTF-8 바이트 제한을 초과하는 비밀번호를 거부한다.
    @JsonIgnore
    @AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // 로그인 자격 증명을 진단 문자열 출력에서 제외한다.
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
