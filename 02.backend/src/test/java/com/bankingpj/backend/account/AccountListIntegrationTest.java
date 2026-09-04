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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
class AccountListIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(70000000000000L);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AccessTokenIssuer accessTokens;

    // 두 회원의 계좌가 섞여 있어도 인증 회원 계좌만 ID 오름차순과 정확한 필드로 반환하는지 검증한다.
    @Test
    void returnsOnlyAuthenticatedUsersAccountsInAccountIdOrder() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        User other = createUser(UserStatus.ACTIVE);
        Account first = createAccount(owner, new BigDecimal("10.1234"), AccountStatus.ACTIVE);
        Account otherFirst = createAccount(other, new BigDecimal("20.0000"), AccountStatus.ACTIVE);
        Account second = createAccount(owner, new BigDecimal("30.5678"), AccountStatus.SUSPENDED);
        Account otherSecond = createAccount(other, new BigDecimal("40.0000"), AccountStatus.CLOSED);

        MvcResult result = listAccounts(accessToken(owner)).andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");

        assertThat(data.size()).isEqualTo(2);
        assertThat(data.get(0).get("accountId").asLong()).isEqualTo(first.getAccountId());
        assertThat(data.get(1).get("accountId").asLong()).isEqualTo(second.getAccountId());
        assertThat(data.get(0).get("accountNumber").asString()).isEqualTo(first.getAccountNumber());
        assertThat(data.get(0).get("balance").decimalValue()).isEqualByComparingTo("10.1234");
        assertThat(data.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(data.get(0).get("createdAt").isNull()).isFalse();
        assertThat(data.get(0).size()).isEqualTo(5);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(otherFirst.getAccountNumber(), otherSecond.getAccountNumber());
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("password", "passwordhash", "accesstoken", "refreshtoken");

        List<Account> persisted = accounts.findAllByUser_UserIdOrderByAccountIdAsc(owner.getUserId());
        assertThat(persisted).extracting(account -> account.getUser().getUserId())
                .containsOnly(owner.getUserId());
        assertThat(persisted).extracting(Account::getAccountId)
                .containsExactly(first.getAccountId(), second.getAccountId());
    }

    // 정상 회원에게 계좌가 없으면 오류 대신 HTTP 200과 빈 배열을 반환하는지 검증한다.
    @Test
    void returnsEmptyArrayWhenUserHasNoAccounts() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        listAccounts(accessToken(owner))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":[],"error":null}
                        """, JsonCompareMode.STRICT));
    }

    // 인증 회원의 ACTIVE·SUSPENDED·CLOSED 계좌를 필터링 없이 모두 반환하는지 검증한다.
    @Test
    void returnsAccountsOfEveryStatus() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
        createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.SUSPENDED);
        createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.CLOSED);

        MvcResult result = listAccounts(accessToken(owner)).andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        List<String> statuses = new ArrayList<>();
        data.forEach(account -> statuses.add(account.get("status").asString()));
        assertThat(statuses).containsExactly("ACTIVE", "SUSPENDED", "CLOSED");
    }

    // Access Token이 없으면 목록 조회 전에 공통 AUTH_003 응답을 반환하는지 검증한다.
    @Test
    void missingAccessTokenReturnsAuth003() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 서명이 변조된 Access Token으로 계좌 목록을 조회할 수 없는지 검증한다.
    @Test
    void tamperedAccessTokenReturnsAuth003() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        listAccounts(tamper(accessToken(owner)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // Query와 Header로 다른 회원 ID를 보내도 JWT 소유자의 계좌만 반환하는지 검증한다.
    @Test
    void clientSuppliedUserIdCannotChangeAccountOwnerScope() throws Exception {
        User owner = createUser(UserStatus.ACTIVE);
        User other = createUser(UserStatus.ACTIVE);
        Account owned = createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
        Account foreign = createAccount(other, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);

        MvcResult result = mvc.perform(get("/api/accounts")
                        .queryParam("userId", other.getUserId().toString())
                        .header("X-User-ID", other.getUserId().toString())
                        .header("Authorization", "Bearer " + accessToken(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).get("accountNumber").asString()).isEqualTo(owned.getAccountNumber());
        assertThat(result.getResponse().getContentAsString()).doesNotContain(foreign.getAccountNumber());
    }

    // ACTIVE가 아닌 회원은 유효한 기존 Access Token으로도 목록을 조회하지 못하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveUserCannotListAccounts(UserStatus status) throws Exception {
        User owner = createUser(status);
        createAccount(owner, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
        listAccounts(accessToken(owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_002"));
    }

    // 상태별 테스트 회원을 실제 BCrypt 해시와 함께 격리된 MySQL에 저장한다.
    private User createUser(UserStatus status) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("account-list-password"), "Account List User", status));
    }

    // 소유자·잔액·상태를 지정한 계좌를 테스트 데이터로 저장한다.
    private Account createAccount(User user, BigDecimal balance, AccountStatus status) {
        String accountNumber = Long.toString(ACCOUNT_SEQUENCE.incrementAndGet());
        return accounts.saveAndFlush(new Account(user, accountNumber, balance, status));
    }

    // 기존 JWT 발급 정책으로 테스트 회원의 Access Token을 생성한다.
    private String accessToken(User user) {
        return accessTokens.issue(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // Bearer Token으로 현재 회원의 계좌 목록 조회 요청을 전송한다.
    private org.springframework.test.web.servlet.ResultActions listAccounts(String accessToken) throws Exception {
        return mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + accessToken));
    }

    // JWT 서명 첫 문자를 변경하여 검증에 실패하는 토큰을 만든다.
    private String tamper(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }
}
