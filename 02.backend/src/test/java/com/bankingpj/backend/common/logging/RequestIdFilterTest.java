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

    @AfterEach
    void cleanUpMdc() {
        MDC.remove("requestId");
        MDC.remove("otherContext");
    }

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

    @Test
    void preservesMdcEntriesOwnedByOtherComponents() throws Exception {
        MDC.put("otherContext", "existing-value");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertThat(MDC.get("otherContext")).isEqualTo("existing-value");
        });

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("otherContext")).isEqualTo("existing-value");
    }

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
