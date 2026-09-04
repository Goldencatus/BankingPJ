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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class AccountCreationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AccessTokenIssuer accessTokens;
    @Autowired private JdbcTemplate jdbc;

    // 정상 인증 회원이 서버 초기값을 가진 계좌를 생성하고 HTTP 201을 받는지 검증한다.
    @Test
    void activeUserCreatesAccountWithExpectedResponseAndDatabaseValues() throws Exception {
        User user = createUser(UserStatus.ACTIVE);
        String accessToken = accessToken(user);

        MvcResult result = createAccount(accessToken, null).andExpect(status().isCreated()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Long accountId = data.get("accountId").asLong();
        String accountNumber = data.get("accountNumber").asString();

        assertThat(accountNumber).matches("[0-9]{14}");
        assertThat(data.get("balance").decimalValue()).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(data.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(data.get("createdAt").isNull()).isFalse();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(accessToken, "password", "passwordHash", "refreshToken", "accessToken");

        Account saved = accounts.findById(accountId).orElseThrow();
        assertThat(saved.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(saved.getBalance()).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(saved.getBalance().scale()).isEqualTo(4);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT user_id FROM accounts WHERE account_id = ?",
                Long.class, accountId)).isEqualTo(user.getUserId());
        assertThat(jdbc.queryForObject("SELECT CAST(balance AS CHAR) FROM accounts WHERE account_id = ?",
                String.class, accountId)).isEqualTo("0.0000");
    }

    // Access Token이 없으면 계좌 생성 전에 공통 AUTH_003 응답을 반환하는지 검증한다.
    @Test
    void missingAccessTokenReturnsAuth003() throws Exception {
        mvc.perform(post("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"AUTH_003","message":"인증이 필요하거나 Access Token이 유효하지 않음"}}
                        """, JsonCompareMode.STRICT));
    }

    // 요청 Body의 소유자와 초기값을 무시하고 JWT 회원 및 서버 정책만 사용하는지 검증한다.
    @Test
    void clientCannotOverrideOwnerOrInitialAccountValues() throws Exception {
        User authenticated = createUser(UserStatus.ACTIVE);
        User other = createUser(UserStatus.ACTIVE);
        String body = """
                {"userId":%d,"accountNumber":"99999999999999","balance":999999,"status":"CLOSED"}
                """.formatted(other.getUserId());

        MvcResult result = createAccount(accessToken(authenticated), body)
                .andExpect(status().isCreated()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Account saved = accounts.findById(data.get("accountId").asLong()).orElseThrow();

        assertThat(saved.getUser().getUserId()).isEqualTo(authenticated.getUserId());
        assertThat(saved.getUser().getUserId()).isNotEqualTo(other.getUserId());
        assertThat(saved.getAccountNumber()).isNotEqualTo("99999999999999");
        assertThat(saved.getBalance()).isEqualByComparingTo("0.0000");
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    // ACTIVE가 아닌 회원의 유효한 Access Token도 계좌를 생성하지 못하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveUserCannotCreateAccount(UserStatus userStatus) throws Exception {
        User user = createUser(userStatus);
        long before = accounts.count();

        createAccount(accessToken(user), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_002"));
        assertThat(accounts.count()).isEqualTo(before);
    }

    // 상태별 테스트 회원을 실제 BCrypt 해시와 함께 격리된 MySQL에 저장한다.
    private User createUser(UserStatus status) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("account-test-password"), "Account Test User", status));
    }

    // 기존 발급 정책으로 회원 식별자와 역할이 포함된 Access Token을 생성한다.
    private String accessToken(User user) {
        return accessTokens.issue(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // 선택적인 공격 Body와 Bearer Token을 포함해 실제 MVC·Security 체인으로 요청한다.
    private org.springframework.test.web.servlet.ResultActions createAccount(String accessToken, String body)
            throws Exception {
        var request = post("/api/accounts").header("Authorization", "Bearer " + accessToken);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}
