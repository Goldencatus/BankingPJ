package com.bankingpj.backend.common.security;

import com.bankingpj.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    // 인증 실패 응답을 생성할 공통 작성기를 주입받는다.
    public RestAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    // 누락되거나 유효하지 않은 인증을 토큰 상세가 없는 AUTH_003 응답으로 처리한다.
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        responseWriter.write(response, ErrorCode.INVALID_ACCESS_TOKEN);
    }
}
