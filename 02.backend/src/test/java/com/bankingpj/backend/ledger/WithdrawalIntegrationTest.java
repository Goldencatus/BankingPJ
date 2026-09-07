package com.bankingpj.backend.ledger;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class WithdrawalIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(92000000000000L);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AccessTokenIssuer accessTokens;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntries;

    // 100000.0000원 계좌에서 30000.0000원을 출금하고 응답·잔액·DEBIT 원장을 검증한다.
    @Test
    void withdrawsFromActiveAccountAndCreatesDebitLedgerEntry() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"), AccountStatus.ACTIVE);
        String accessToken = accessToken(owner);

        MvcResult result = withdraw(accessToken, account.getAccountId(), "30000.0000")
                .andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        Account saved = accounts.findById(account.getAccountId()).orElseThrow();
        List<LedgerEntry> entries = ledgerEntries
                .findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId());

        assertThat(data.size()).isEqualTo(4);
        assertThat(data.path("accountId").asLong()).isEqualTo(account.getAccountId());
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("30000.0000");
        assertThat(data.path("balanceAfter").decimalValue()).isEqualByComparingTo("70000.0000");
        assertThat(data.path("createdAt").isNull()).isFalse();
        assertThat(saved.getBalance()).isEqualByComparingTo("70000.0000");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("-30000.0000");
        assertThat(entries.get(0).getBalanceAfter()).isEqualByComparingTo(saved.getBalance());
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("password", "passwordhash", "accesstoken", "refreshtoken", "accountnumber");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(accessToken);
    }

    // 계좌 잔액 전체를 출금하면 음수 잔액 없이 정확히 0.0000으로 처리되는지 검증한다.
    @Test
    void withdrawsEntireBalance() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"), AccountStatus.ACTIVE);

        withdraw(accessToken(owner), account.getAccountId(), "100000.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.0000");
    }

    // 소수점 네 자리 출금 결과를 BigDecimal로 읽어 계산값이 정확히 유지되는지 검증한다.
    @Test
    void preservesExactBigDecimalWithdrawal() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("10.0000"), AccountStatus.ACTIVE);

        MvcResult result = withdraw(accessToken(owner), account.getAccountId(), "0.1234")
                .andExpect(status().isOk()).andReturn();
        BigDecimal responseBalance = mapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("balanceAfter").decimalValue();

        assertThat(responseBalance).isEqualByComparingTo("9.8766");
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo("9.8766");
    }

    // null·0·음수·scale 초과·범위 초과 출금액을 공통 400 오류로 거부하는지 검증한다.
    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1.0000", "0.00001", "1000000000000000.0000"})
    void invalidAmountsReturnCommon001(String amountLiteral) throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);

        withdraw(accessToken(owner), account.getAccountId(), amountLiteral)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo("100.0000");
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId())).isEmpty();
    }

    // 잔액보다 0.0001 큰 출금을 ACCOUNT_003으로 거부하고 잔액과 원장을 유지하는지 검증한다.
    @Test
    void insufficientBalanceReturnsAccount003WithoutChanges() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("50000.0000"), AccountStatus.ACTIVE);

        withdraw(accessToken(owner), account.getAccountId(), "50000.0001")
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"ACCOUNT_003","message":"계좌 잔액이 부족합니다."}}
                        """, JsonCompareMode.STRICT));
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50000.0000");
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId())).isEmpty();
    }

    // 없는 계좌와 타인 계좌가 동일한 ACCOUNT_001을 반환하며 계좌 정보를 노출하지 않는지 검증한다.
    @Test
    void missingAndForeignAccountsReturnIdenticalAccount001() throws Exception {
        User requester = createUser();
        User other = createUser();
        Account foreign = createAccount(other, new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        String accessToken = accessToken(requester);

        MvcResult foreignResult = withdraw(accessToken, foreign.getAccountId(), "1.0000")
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missingResult = withdraw(accessToken, Long.MAX_VALUE, "1.0000")
                .andExpect(status().isNotFound()).andReturn();

        content().json("""
                {"success":false,"data":null,
                 "error":{"code":"ACCOUNT_001","message":"계좌를 찾을 수 없습니다."}}
                """, JsonCompareMode.STRICT).match(foreignResult);
        assertThat(foreignResult.getResponse().getContentAsString())
                .isEqualTo(missingResult.getResponse().getContentAsString())
                .doesNotContain(foreign.getAccountNumber(), foreign.getBalance().toPlainString());
    }

    // SUSPENDED·CLOSED 계좌 출금을 ACCOUNT_002로 차단하고 잔액과 원장을 유지하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void unavailableAccountCannotBeWithdrawn(AccountStatus status) throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("10.0000"), status);

        withdraw(accessToken(owner), account.getAccountId(), "1.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_002"));
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo("10.0000");
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId())).isEmpty();
    }

    // Access Token이 없으면 계좌나 출금액 처리 전에 AUTH_003을 반환하는지 검증한다.
    @Test
    void missingAccessTokenReturnsAuth003() throws Exception {
        mvc.perform(post("/api/accounts/{accountId}/withdrawals", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.0000}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 원장 저장 실패 시 같은 트랜잭션의 계좌 잔액 감소도 원래 값으로 롤백되는지 검증한다.
    @Test
    void ledgerFailureRollsBackAccountBalance() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("50.0000"), AccountStatus.ACTIVE);
        doThrow(new DataIntegrityViolationException("의도한 테스트 원장 저장 실패"))
                .when(ledgerEntries).saveAndFlush(any(LedgerEntry.class));

        MvcResult result = withdraw(accessToken(owner), account.getAccountId(), "25.0000")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"))
                .andReturn();

        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50.0000");
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId())).isEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("의도한 테스트 원장 저장 실패");
    }

    // ACTIVE 상태 테스트 회원을 실제 BCrypt 해시와 함께 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("withdrawal-test-password"), "Withdrawal Test User", UserStatus.ACTIVE));
    }

    // 지정한 잔액과 상태의 테스트 계좌를 저장한다.
    private Account createAccount(User owner, BigDecimal balance, AccountStatus status) {
        return accounts.saveAndFlush(new Account(owner,
                Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()), balance, status));
    }

    // 기존 JWT 발급 정책으로 테스트 회원의 Access Token을 생성한다.
    private String accessToken(User user) {
        return accessTokens.issue(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // 출금액을 JSON 숫자로 전달하여 실제 MVC·Security·Transaction 흐름을 호출한다.
    private ResultActions withdraw(String accessToken, long accountId, String amountLiteral) throws Exception {
        return mvc.perform(post("/api/accounts/{accountId}/withdrawals", accountId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amountLiteral + "}"));
    }
}
