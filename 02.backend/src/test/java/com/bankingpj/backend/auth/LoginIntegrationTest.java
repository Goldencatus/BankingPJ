package com.bankingpj.backend.auth;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.domain.RefreshToken;
import com.bankingpj.backend.auth.dto.LoginRequest;
import com.bankingpj.backend.auth.repository.RefreshTokenRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class LoginIntegrationTest {

    private static final String PASSWORD = "login-test-password";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private AuthProperties properties;
    @Autowired private JdbcTemplate jdbc;

    // 익명 로그인이 지정된 응답 필드만 반환하고 세션을 생성하지 않는지 검증한다.
    @Test
    void successfulLoginReturnsAccessTokenWithoutSensitiveResponseFields() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        MvcResult result = login(user.getEmail(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(properties.accessTtlSeconds()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "login-test-request"))
                .andReturn();

        JsonNode root = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.size()).isEqualTo(3);
        assertThat(root.get("error").isNull()).isTrue();
        assertThat(root.get("data").size()).isEqualTo(3);
        String body = result.getResponse().getContentAsString();
        for (String forbidden : new String[]{"password", "passwordHash", "refreshToken", "refresh_token",
                PASSWORD, user.getPasswordHash(), refreshCookie(result).getValue()}) {
            assertThat(body.contains(forbidden)).isFalse();
        }
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // 실제 발급된 JWT의 서명과 최소 claim 구성이 올바른지 검증한다.
    @Test
    void accessTokenHasValidSignatureAndOnlyRequiredClaims() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        MvcResult result = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();

        Jwt jwt = decoder.decode(accessToken(result));

        assertThat(jwt.getSubject()).isEqualTo(user.getUserId().toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("bankingpj");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getHeaders().get("typ")).isEqualTo("JWT");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds())
                .isEqualTo(properties.accessTtlSeconds());
        assertThat(UUID.fromString(jwt.getId()).toString()).isEqualTo(jwt.getId());
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "role", "iss", "iat", "exp", "jti");
    }

    // Refresh 쿠키의 HttpOnly·경로·SameSite·Secure 설정과 수명을 검증한다.
    @Test
    void refreshCookieUsesConfiguredScopeAndLifetime() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        MvcResult result = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();

        Cookie cookie = refreshCookie(result);

        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getMaxAge()).isEqualTo((int) properties.refreshTtlSeconds());
        assertThat(Base64.getUrlDecoder().decode(cookie.getValue()).length).isEqualTo(32);
        assertThat(cookie.getValue().contains(".")).isFalse();
    }

    // DB 커밋 결과에 원문 대신 독립적으로 계산한 SHA-256 해시가 저장되는지 검증한다.
    @Test
    void persistsOnlyRefreshHashWithMatchingUserAndExpiry() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        MvcResult result = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();
        String rawToken = refreshCookie(result).getValue();
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8)));

        RefreshToken saved = refreshTokens.findByTokenHash(expectedHash).orElseThrow();

        assertThat(saved.getRefreshTokenId()).isNotNull();
        assertThat(saved.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(saved.getTokenHash().equals(rawToken)).isFalse();
        assertThat(saved.getTokenHash()).isEqualTo(expectedHash);
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getExpiresAt()).isEqualTo(saved.getCreatedAt().plusSeconds(properties.refreshTtlSeconds()));
        Jwt jwt = decoder.decode(accessToken(result));
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(jwt.getIssuedAt(), ZoneOffset.UTC));
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class))
                .contains("5");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE refresh_token_id = ?",
                String.class, saved.getRefreshTokenId())).isEqualTo(expectedHash);
    }

    // 없는 이메일과 틀린 비밀번호가 동일한 공개 오류를 반환하고 토큰을 저장하지 않는지 검증한다.
    @Test
    void wrongPasswordAndUnknownEmailReturnIdenticalAuth001WithoutTokens() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        long before = refreshTokens.count();

        MvcResult wrongPassword = login(user.getEmail(), "incorrect-password")
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownEmail = login(UUID.randomUUID() + "@example.com", PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn();

        for (MvcResult result : new MvcResult[]{wrongPassword, unknownEmail}) {
            content().json("""
                    {"success":false,"data":null,
                     "error":{"code":"AUTH_001","message":"잘못된 로그인 정보"}}
                    """, JsonCompareMode.STRICT).match(result);
            assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        }
        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownEmail.getResponse().getContentAsString());
        assertThat(refreshTokens.count()).isEqualTo(before);
    }

    // 비밀번호가 맞아도 비활성 계정에는 토큰과 쿠키를 발급하지 않는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveUserReturnsAuth002WithoutTokens(UserStatus status) throws Exception {
        User user = createUser(status);
        long before = refreshTokens.count();

        login(user.getEmail(), PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"AUTH_002","message":"로그인할 수 없는 사용자 상태"}}
                        """, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(refreshTokens.count()).isEqualTo(before);
    }

    // 틀린 비밀번호로 계정의 정지 상태를 알아낼 수 없는지 검증한다.
    @Test
    void wrongPasswordIsCheckedBeforeAccountStatus() throws Exception {
        User user = createUser(UserStatus.SUSPENDED);
        login(user.getEmail(), "incorrect-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // 잘못된 로그인 입력이 토큰 생성·저장 전에 검증 오류로 거부되는지 확인한다.
    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidLoginRequestReturnsCommon001(LoginRequest request) throws Exception {
        long before = refreshTokens.count();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(refreshTokens.count()).isEqualTo(before);
    }

    // 필수값·이메일 형식·BCrypt 바이트 제한을 위반하는 테스트 입력을 제공한다.
    static Stream<LoginRequest> invalidRequests() {
        return Stream.of(new LoginRequest(null, PASSWORD), new LoginRequest("invalid", PASSWORD),
                new LoginRequest("user@example.com", null), new LoginRequest("user@example.com", " "),
                new LoginRequest("user@example.com", "a".repeat(73)),
                new LoginRequest("user@example.com", "가".repeat(25)));
    }

    // 반복 로그인에서 JWT ID와 Refresh Token이 각각 새로 생성되는지 검증한다.
    @Test
    void repeatedLoginsIssueDistinctTokens() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        MvcResult first = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();
        MvcResult second = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();

        assertThat(decoder.decode(accessToken(first)).getId())
                .isNotEqualTo(decoder.decode(accessToken(second)).getId());
        assertThat(refreshCookie(first).getValue().equals(refreshCookie(second).getValue())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?",
                Long.class, user.getUserId())).isEqualTo(2L);
    }

    // 로그인에서 발급된 Access Token으로 인증하고 Refresh 쿠키만으로는 인증하지 않는지 검증한다.
    @Test
    void accessTokenAuthenticatesCurrentUserButRefreshCookieDoesNot() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        MvcResult result = login(user.getEmail(), PASSWORD).andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken(result)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"userId":%d,"role":"USER"},"error":null}
                        """.formatted(user.getUserId()), JsonCompareMode.STRICT));
        mvc.perform(get("/api/users/me").cookie(refreshCookie(result)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 실제 비밀번호 인코더로 해싱한 테스트 계정을 격리된 DB에 저장한다.
    private User createUser(UserStatus status) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode(PASSWORD), "Login Test User", status));
    }

    // 실제 MVC·Security 필터를 거치는 익명 JSON 로그인 요청을 전송한다.
    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").header("X-Request-ID", "login-test-request")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new LoginRequest(email, password))));
    }

    // 출력 없이 메모리에서 검증할 Access Token을 응답에서 추출한다.
    private String accessToken(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asString();
    }

    // 속성과 저장 내용을 검사할 Refresh 쿠키가 존재하는지 확인하고 반환한다.
    private Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
