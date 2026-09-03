package com.bankingpj.backend.auth;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.dto.LoginRequest;
import com.bankingpj.backend.auth.dto.LoginResponse;
import com.bankingpj.backend.auth.dto.LoginResult;
import com.bankingpj.backend.auth.token.RefreshTokenCookieFactory;
import com.bankingpj.backend.auth.token.RefreshTokenGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseCookie;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TokenSupportTest {

    // Apply the Secure setting independently of the common cookie scope and lifetime.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cookieHonorsEnvironmentSecureFlag(boolean secure) {
        ResponseCookie cookie = new RefreshTokenCookieFactory(new AuthProperties("unused", 900, 3600, secure))
                .create("test-token");
        assertThat(cookie.isSecure()).isEqualTo(secure);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(3600));
    }

    // Opaque token generation must produce independent URL-safe 256-bit values.
    @Test
    void generatesIndependentOpaqueTokens() {
        RefreshTokenGenerator generator = new RefreshTokenGenerator();
        String first = generator.generate();
        String second = generator.generate();
        assertThat(first.equals(second)).isFalse();
        assertThat(first.matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(Base64.getUrlDecoder().decode(first).length).isEqualTo(32);
        assertThat(generator.hash(first).length()).isEqualTo(64);
        assertThat(generator.hash(first)).isEqualTo(generator.hash(first));
    }

    // Diagnostic strings and serialization of the internal result must not expose refresh credentials.
    @Test
    void redactsSensitiveDiagnosticStringsAndInternalRefreshValue() {
        LoginResponse response = new LoginResponse("test-access-token", "Bearer", 900);
        LoginResult result = new LoginResult(response, "test-refresh-token");
        assertThat(response.toString()).doesNotContain("test-access-token");
        assertThat(result.toString()).doesNotContain("test-refresh-token", "test-access-token");
        assertThat(new LoginRequest("test@example.com", "test-password").toString()).doesNotContain("test-password");
        assertThat(new AuthProperties("test-secret", 900, 3600, true).toString()).doesNotContain("test-secret");
        assertThat(JsonMapper.builder().build().writeValueAsString(result).contains("test-refresh-token")).isFalse();
    }
}
