package com.bankingpj.backend.common.security;

import com.bankingpj.backend.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;

    // 인가 실패 응답을 생성할 공통 작성기를 주입받는다.
    public RestAccessDeniedHandler(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    // 인증된 사용자의 권한 부족을 내부 예외 정보가 없는 AUTH_004 응답으로 처리한다.
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        responseWriter.write(response, ErrorCode.ACCESS_DENIED);
    }
}
