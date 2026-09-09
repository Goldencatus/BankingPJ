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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class AccountStatusIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(99000000000000L);

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AccessTokenIssuer accessTokens;

    // ACTIVE 본인 계좌를 SUSPENDED로 변경하고 최신 수정 시각을 반환하는지 검증한다.
    @Test
    void activeAccountCanBeSuspended() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, "0.0000", AccountStatus.ACTIVE);

        changeStatus(owner, account.getAccountId(), "suspend")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(account.getAccountId()))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.SUSPENDED);
    }

    // SUSPENDED 본인 계좌를 ACTIVE로 되돌릴 수 있는지 검증한다.
    @Test
    void suspendedAccountCanBeActivated() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, "0.0000", AccountStatus.SUSPENDED);

        changeStatus(owner, account.getAccountId(), "activate")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    // 잔액이 0인 ACTIVE·SUSPENDED 계좌를 CLOSED로 변경할 수 있는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"ACTIVE", "SUSPENDED"})
    void zeroBalanceAccountCanBeClosed(AccountStatus initialStatus) throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, "0.0000", initialStatus);

        changeStatus(owner, account.getAccountId(), "close")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    // 잔액이 남은 계좌 해지는 ACCOUNT_005로 거부되고 상태가 유지되는지 검증한다.
    @Test
    void nonZeroBalanceAccountCannotBeClosed() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, "0.0001", AccountStatus.ACTIVE);

        changeStatus(owner, account.getAccountId(), "close")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_005"));
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    // CLOSED 계좌의 활성화·일시정지·중복 해지를 모두 ACCOUNT_004로 거부하는지 검증한다.
    @ParameterizedTest
    @ValueSource(strings = {"activate", "suspend", "close"})
    void closedAccountCannotChangeStatus(String command) throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, "0.0000", AccountStatus.CLOSED);

        changeStatus(owner, account.getAccountId(), command)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_004"));
    }

    // 다른 사용자의 계좌와 없는 계좌를 동일한 ACCOUNT_001로 숨기는지 검증한다.
    @Test
    void foreignAndMissingAccountsReturnAccount001() throws Exception {
        User requester = createUser();
        Account foreign = createAccount(createUser(), "0.0000", AccountStatus.ACTIVE);

        changeStatus(requester, foreign.getAccountId(), "suspend")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_001"));
        changeStatus(requester, Long.MAX_VALUE, "suspend")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_001"));
    }

    // 상태변경 테스트에 사용할 ACTIVE 회원을 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("account-status-password"), "Account Status User", UserStatus.ACTIVE));
    }

    // 상태와 잔액을 지정한 계좌를 실제 MySQL에 저장한다.
    private Account createAccount(User owner, String balance, AccountStatus status) {
        return accounts.saveAndFlush(new Account(owner, Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()),
                new BigDecimal(balance), status));
    }

    // 기존 JWT 인증과 명시적인 상태 업무 명령 Endpoint로 요청한다.
    private org.springframework.test.web.servlet.ResultActions changeStatus(
            User owner, long accountId, String command) throws Exception {
        String token = accessTokens.issue(owner, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        return mvc.perform(post("/api/accounts/{accountId}/{command}", accountId, command)
                .header("Authorization", "Bearer " + token));
    }
}
