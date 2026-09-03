package com.bankingpj.backend.common.logging;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    // 테스트가 사용한 MDC 값을 제거해 다른 테스트와 격리한다.
    @AfterEach
    void cleanUpMdc() {
        MDC.remove("requestId");
        MDC.remove("otherContext");
    }

    // 입력 요청 ID가 처리 중 MDC와 응답 헤더에 유지되는지 검증한다.
    @Test
    void echoesProvidedRequestIdAndExposesItInMdcDuringProcessing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "client-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            chainInvoked.set(true);
            assertThat(MDC.get("requestId")).isEqualTo("client-request-123");
            assertThat(response.getHeader("X-Request-ID")).isEqualTo("client-request-123");
            response.flushBuffer();
        });

        assertThat(chainInvoked).isTrue();
        assertThat(response.getHeaders("X-Request-ID")).containsExactly("client-request-123");
        assertThat(MDC.get("requestId")).isNull();
    }

    // 헤더 없는 요청에 새 ID를 부여하고 종료 시 MDC를 정리하는지 검증한다.
    @Test
    void addsRequestIdToResponseWhenRequestHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            chainInvoked.set(true);
            assertThat(MDC.get("requestId"))
                    .isNotBlank()
                    .isEqualTo(response.getHeader("X-Request-ID"));
        });

        assertThat(chainInvoked).isTrue();
        assertThat(response.getHeader("X-Request-ID")).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
    }

    // 자동 생성된 요청 ID가 UUID v4 형식인지 검증한다.
    @Test
    void generatedRequestIdIsCanonicalRandomUuid() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, servletResponse) -> { });

        String requestId = response.getHeader("X-Request-ID");
        assertThat(requestId).isNotBlank();
        UUID uuid = UUID.fromString(requestId);
        assertThat(uuid.toString()).isEqualTo(requestId);
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(MDC.get("requestId")).isNull();
    }

    // 후속 필터에서 예외가 발생해도 MDC 요청 ID를 제거하는지 검증한다.
    @Test
    void removesRequestIdEvenWhenFilterChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "failed-request");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("Test failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get("requestId")).isEqualTo("failed-request");
            throw failure;
        })).isSameAs(failure);

        assertThat(response.getHeader("X-Request-ID")).isEqualTo("failed-request");
        assertThat(MDC.get("requestId")).isNull();
    }

    // 요청 ID 정리 시 다른 구성 요소의 MDC 값은 보존하는지 검증한다.
    @Test
    void preservesMdcEntriesOwnedByOtherComponents() throws Exception {
        MDC.put("otherContext", "existing-value");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertThat(MDC.get("otherContext")).isEqualTo("existing-value");
        });

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("otherContext")).isEqualTo("existing-value");
    }

    // 같은 스레드의 연속 요청에 서로 다른 ID를 부여하는지 검증한다.
    @Test
    void generatesDistinctIdsForSequentialRequestsOnSameThread() throws Exception {
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), firstResponse, (request, response) -> { });
        assertThat(MDC.get("requestId")).isNull();

        filter.doFilter(new MockHttpServletRequest(), secondResponse, (request, response) -> {
            assertThat(MDC.get("requestId"))
                    .isEqualTo(secondResponse.getHeader("X-Request-ID"))
                    .isNotEqualTo(firstResponse.getHeader("X-Request-ID"));
        });

        assertThat(secondResponse.getHeader("X-Request-ID"))
                .isNotBlank()
                .isNotEqualTo(firstResponse.getHeader("X-Request-ID"));
        assertThat(MDC.get("requestId")).isNull();
    }
}
