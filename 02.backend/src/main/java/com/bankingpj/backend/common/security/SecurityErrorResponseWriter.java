package com.bankingpj.backend.common.security;

import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    // 애플리케이션의 공통 JSON 직렬화 설정을 주입받는다.
    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 예외 상세 없이 공개 오류 코드와 메시지만 공통 JSON 응답에 기록한다.
    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.failure(errorCode.getCode(), errorCode.getMessage()));
    }
}
