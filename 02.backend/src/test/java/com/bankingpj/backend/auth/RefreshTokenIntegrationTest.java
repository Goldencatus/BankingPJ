package com.bankingpj.backend.auth;

import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.domain.RefreshToken;
import com.bankingpj.backend.auth.repository.RefreshTokenRepository;
import com.bankingpj.backend.auth.token.RefreshTokenGenerator;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RefreshTokenIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private RefreshTokenGenerator generator;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthProperties properties;

    // 각 테스트가 독립적인 회원과 Refresh Token 상태에서 시작하도록 정리한다.
    @BeforeEach
    void cleanDatabase() {
        tokens.deleteAll();
        accounts.deleteAll();
        users.deleteAll();
    }

    // 정상 토큰을 Rotation하여 새 Access Token과 HttpOnly Refresh 쿠키를 반환하는지 검증한다.
    @Test
    void refreshReturnsNewAccessTokenAndCookie() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);

        MvcResult result = refresh(original.raw()).andExpect(status().isOk()).andReturn();

        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Cookie refreshCookie = result.getResponse().getCookie("refresh_token");
        assertThat(data.get("accessToken").asString()).isNotBlank();
        assertThat(data.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(data.get("expiresIn").asLong()).isEqualTo(properties.accessTtlSeconds());
        assertRefreshCookie(refreshCookie);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(refreshCookie.getValue(), "refreshToken", "password", "passwordHash");
    }

    // 성공한 Rotation이 이전 행을 폐기하고 새 해시 행을 저장하는지 검증한다.
    @Test
    void rotationRevokesOldRowAndPersistsOnlyNewHash() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);

        MvcResult result = refresh(original.raw()).andExpect(status().isOk()).andReturn();
        String newRaw = result.getResponse().getCookie("refresh_token").getValue();

        RefreshToken oldToken = tokens.findById(original.id()).orElseThrow();
        RefreshToken newToken = tokens.findByTokenHash(generator.hash(newRaw)).orElseThrow();
        assertThat(oldToken.getRevokedAt()).isNotNull();
        assertThat(newToken.getRefreshTokenId()).isNotEqualTo(original.id());
        assertThat(newToken.getTokenHash()).isNotEqualTo(newRaw);
        assertThat(newToken.getTokenHash()).isEqualTo(generator.hash(newRaw));
        assertThat(tokens.count()).isEqualTo(2);
    }

    // Rotation된 이전 토큰을 재사용하면 상세 원인 없이 AUTH_005를 반환하는지 검증한다.
    @Test
    void rotatedTokenCannotBeReused() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);
        refresh(original.raw()).andExpect(status().isOk());
        assertInvalidRefresh(refresh(original.raw()));
    }

    // Refresh 쿠키가 없으면 DB 처리 없이 AUTH_005를 반환하는지 검증한다.
    @Test
    void missingCookieReturnsAuth005() throws Exception {
        assertInvalidRefresh(mvc.perform(post("/api/auth/refresh")));
    }

    // DB에 없는 Refresh Token을 외부 상세 없이 AUTH_005로 통일하는지 검증한다.
    @Test
    void unknownTokenReturnsAuth005() throws Exception {
        assertInvalidRefresh(refresh("unknown-refresh-token"));
    }

    // 만료된 Refresh Token이 새 토큰을 발급하지 않고 AUTH_005를 반환하는지 검증한다.
    @Test
    void expiredTokenReturnsAuth005() throws Exception {
        IssuedToken token = issueStoredToken(createUser(UserStatus.ACTIVE), -1, false);
        assertInvalidRefresh(refresh(token.raw()));
    }

    // 이미 폐기된 Refresh Token이 새 토큰을 발급하지 않고 AUTH_005를 반환하는지 검증한다.
    @Test
    void revokedTokenReturnsAuth005() throws Exception {
        IssuedToken token = issueStoredToken(createUser(UserStatus.ACTIVE), 60, true);
        assertInvalidRefresh(refresh(token.raw()));
    }

    // ACTIVE가 아닌 회원의 Refresh Token을 재발급에 사용하지 못하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveUserCannotRefresh(UserStatus status) throws Exception {
        IssuedToken token = issueStoredToken(createUser(status), 60, false);
        assertInvalidRefresh(refresh(token.raw()));
    }

    // 같은 Refresh Token의 동시 요청 중 하나만 성공하고 다른 하나는 AUTH_005인지 검증한다.
    @Test
    void concurrentRefreshAllowsOnlyOneRotation() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return refresh(original.raw()).andReturn();
                }));
            }
            ready.await();
            start.countDown();
            List<MvcResult> results = List.of(futures.get(0).get(), futures.get(1).get());
            List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus())
                    .sorted(Comparator.naturalOrder()).toList();
            assertThat(statuses).containsExactly(200, 401);
            MvcResult failure = results.stream().filter(result -> result.getResponse().getStatus() == 401)
                    .findFirst().orElseThrow();
            assertThat(mapper.readTree(failure.getResponse().getContentAsString())
                    .get("error").get("code").asString()).isEqualTo("AUTH_005");
            assertThat(tokens.count()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    // 정상 Logout이 토큰을 폐기하고 같은 범위의 제거 쿠키를 반환하는지 검증한다.
    @Test
    void logoutRevokesTokenAndDeletesCookie() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);

        MvcResult result = logout(original.raw()).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":null,"error":null}
                        """, JsonCompareMode.STRICT)).andReturn();

        assertThat(tokens.findById(original.id()).orElseThrow().getRevokedAt()).isNotNull();
        Cookie deleted = result.getResponse().getCookie("refresh_token");
        assertThat(deleted).isNotNull();
        assertThat(deleted.getMaxAge()).isZero();
        assertThat(deleted.getPath()).isEqualTo("/api/auth");
        assertThat(deleted.isHttpOnly()).isTrue();
        assertThat(deleted.getAttribute("SameSite")).isEqualTo("Strict");
    }

    // Logout 후 폐기된 토큰으로 재발급할 수 없는지 검증한다.
    @Test
    void loggedOutTokenCannotRefresh() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);
        logout(original.raw()).andExpect(status().isOk());
        assertInvalidRefresh(refresh(original.raw()));
    }

    // 쿠키가 없거나 이미 폐기됐어도 반복 Logout이 같은 성공 상태를 유지하는지 검증한다.
    @Test
    void repeatedLogoutIsIdempotent() throws Exception {
        IssuedToken original = issueStoredToken(createUser(UserStatus.ACTIVE), 60, false);
        logout(original.raw()).andExpect(status().isOk());
        logout(original.raw()).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(tokens.findById(original.id()).orElseThrow().getRevokedAt()).isNotNull();
    }

    // 응답과 로그가 Refresh 원문이나 비밀번호 관련 필드를 노출하지 않는지 검증한다.
    @Test
    void responseAndLogsDoNotExposeSensitiveValues(CapturedOutput output) throws Exception {
        String rawToken = "refresh-secret-" + UUID.randomUUID();
        MvcResult result = refresh(rawToken).andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(rawToken, "password", "passwordHash", "refreshToken");
        assertThat(output.getAll()).doesNotContain(rawToken);
    }

    // 상태와 만료 정책을 지정한 Refresh Token 원문·DB 행 쌍을 생성한다.
    private IssuedToken issueStoredToken(User user, long validForSeconds, boolean revoked) {
        String raw = "test-refresh-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).withNano(0);
        RefreshToken token = new RefreshToken(user, generator.hash(raw), now.plusSeconds(validForSeconds), now.minusSeconds(1));
        if (revoked) {
            token.revoke(now.minusSeconds(1));
        }
        return new IssuedToken(raw, tokens.saveAndFlush(token).getRefreshTokenId());
    }

    // 실제 BCrypt 해시를 가진 상태별 테스트 회원을 MySQL 컨테이너에 저장한다.
    private User createUser(UserStatus status) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("refresh-test-password"), "Refresh Test User", status));
    }

    // Refresh 쿠키만 포함한 익명 POST 요청을 실제 Security·MVC 체인으로 전송한다.
    private ResultActions refresh(String rawToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", rawToken)));
    }

    // Logout 쿠키만 포함한 익명 POST 요청을 실제 Security·MVC 체인으로 전송한다.
    private ResultActions logout(String rawToken) throws Exception {
        return mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", rawToken)));
    }

    // 모든 Refresh 검증 실패가 같은 401 공통 응답이고 쿠키를 발급하지 않는지 확인한다.
    private void assertInvalidRefresh(ResultActions action) throws Exception {
        action.andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"AUTH_005","message":"Refresh Token이 없거나 유효하지 않음"}}
                        """, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    // 새 Refresh 쿠키가 로그인과 같은 보안 속성과 수명을 유지하는지 확인한다.
    private void assertRefreshCookie(Cookie cookie) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getMaxAge()).isEqualTo((int) properties.refreshTtlSeconds());
    }

    private record IssuedToken(String raw, Long id) {
    }
}
