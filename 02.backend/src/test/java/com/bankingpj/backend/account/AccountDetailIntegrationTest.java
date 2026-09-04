package com.bankingpj.backend.account;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class AccountDetailIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(80000000000000L);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AccessTokenIssuer accessTokens;
    @Autowired private JdbcTemplate jdbc;

    // 본인 계좌 상세가 Entity의 공개 필드와 정확히 매핑되고 민감정보를 제외하는지 검증한다.
    @Test
    void returnsOwnedAccountWithExactResponseFields() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        Account account = createAccount(owner, new BigDecimal("12345.6789"), AccountStatus.ACTIVE);

        MvcResult result = accountDetail(accessToken(owner), account.getAccountId())
                .andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");

        assertThat(data.size()).isEqualTo(5);
        assertThat(data.get("accountId").asLong()).isEqualTo(account.getAccountId());
        assertThat(data.get("accountNumber").asString()).isEqualTo(account.getAccountNumber());
        assertThat(data.get("balance").decimalValue()).isEqualByComparingTo(account.getBalance());
        assertThat(data.get("status").asString()).isEqualTo(account.getStatus().name());
        LocalDateTime persistedCreatedAt = jdbc.queryForObject(
                "SELECT created_at FROM accounts WHERE account_id = ?", LocalDateTime.class, account.getAccountId());
        assertThat(LocalDateTime.parse(data.get("createdAt").asString())).isEqualTo(persistedCreatedAt);
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("password", "passwordhash", "accesstoken", "refreshtoken");
    }

    // 타인 계좌와 존재하지 않는 계좌를 같은 ACCOUNT_001 응답으로 처리하는지 검증한다.
    @Test
    void foreignAndMissingAccountsReturnIdenticalAccount001() throws Exception {
        User requester = createUser(UserStatus.ACTIVE);
        User other = createUser(UserStatus.ACTIVE);
        Account foreign = createAccount(other, new BigDecimal("98765.4321"), AccountStatus.ACTIVE);
        String accessToken = accessToken(requester);

        MvcResult foreignResult = accountDetail(accessToken, foreign.getAccountId())
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missingResult = accountDetail(accessToken, Long.MAX_VALUE)
                .andExpect(status().isNotFound()).andReturn();

        content().json("""
                {"success":false,"data":null,
                 "error":{"code":"ACCOUNT_001","message":"계좌를 찾을 수 없습니다."}}
                """, JsonCompareMode.STRICT).match(foreignResult);
        assertThat(foreignResult.getResponse().getContentAsString())
                .isEqualTo(missingResult.getResponse().getContentAsString())
                .doesNotContain(foreign.getAccountNumber(), foreign.getBalance().toPlainString());
    }

    // 본인 소유의 SUSPENDED·CLOSED 계좌도 상태와 함께 상세 조회되는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void returnsOwnedAccountRegardlessOfAccountStatus(AccountStatus status) throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        Account account = createAccount(owner, BigDecimal.ZERO.setScale(4), status);
        accountDetail(accessToken(owner), account.getAccountId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(status.name()));
    }

    // Access Token이 없으면 계좌 존재 여부를 확인하기 전에 AUTH_003을 반환하는지 검증한다.
    @Test
    void missingAccessTokenReturnsAuth003() throws Exception {
        mvc.perform(get("/api/accounts/{accountId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 서명이 변조된 Access Token으로 계좌 상세를 조회할 수 없는지 검증한다.
    @Test
    void tamperedAccessTokenReturnsAuth003() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        Account account = createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
        accountDetail(tamper(accessToken(owner)), account.getAccountId())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 0과 음수 계좌 ID를 공통 검증 오류 400으로 처리하는지 검증한다.
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAccountIdReturnsCommon001(long accountId) throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        accountDetail(accessToken(owner), accountId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    // ACTIVE가 아닌 회원은 기존 Access Token으로도 계좌 상세를 조회하지 못하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveUserCannotReadAccountDetail(UserStatus status) throws Exception {
        User owner = createUser(status);
        Account account = createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
        accountDetail(accessToken(owner), account.getAccountId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_002"));
    }

    // 상태별 테스트 회원을 실제 BCrypt 해시와 함께 격리된 MySQL에 저장한다.
    private User createUser(UserStatus status) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("account-detail-password"), "Account Detail User", status));
    }

    // 소유자와 상태가 지정된 계좌를 상세 조회 테스트 데이터로 저장한다.
    private Account createAccount(User owner, BigDecimal balance, AccountStatus status) {
        return accounts.saveAndFlush(new Account(owner,
                Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()), balance, status));
    }

    // 기존 JWT 발급 정책으로 테스트 회원의 Access Token을 생성한다.
    private String accessToken(User user) {
        return accessTokens.issue(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // 인증 토큰과 Path Variable을 사용해 실제 상세 조회 요청을 전송한다.
    private org.springframework.test.web.servlet.ResultActions accountDetail(String accessToken, long accountId)
            throws Exception {
        return mvc.perform(get("/api/accounts/{accountId}", accountId)
                .header("Authorization", "Bearer " + accessToken));
    }

    // JWT 서명 첫 문자를 변경하여 검증에 실패하는 토큰을 만든다.
    private String tamper(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }
}
