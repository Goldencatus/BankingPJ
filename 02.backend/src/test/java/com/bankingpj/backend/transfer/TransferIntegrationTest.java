package com.bankingpj.backend.transfer;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.transfer.domain.Transfer;
import com.bankingpj.backend.transfer.domain.TransferStatus;
import com.bankingpj.backend.transfer.repository.TransferRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class TransferIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(94000000000000L);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private TransferRepository transfers;
    @Autowired private AccessTokenIssuer accessTokens;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntries;

    // 다른 회원의 ACTIVE 계좌로 이체하고 잔액 총합·Transfer·원장 두 건·응답 보안을 검증한다.
    @Test
    void transfersBetweenActiveAccountsAndPreservesTotalBalance() throws Exception {
        User sender = createUser();
        User recipient = createUser();
        Account fromAccount = createAccount(sender, new BigDecimal("100000.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(recipient, new BigDecimal("20000.0000"), AccountStatus.ACTIVE);
        BigDecimal totalBefore = fromAccount.getBalance().add(toAccount.getBalance());

        MvcResult result = transfer(accessToken(sender), fromAccount.getAccountId(),
                toAccount.getAccountNumber(), "30000.0000").andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");

        Account savedFrom = accounts.findById(fromAccount.getAccountId()).orElseThrow();
        Account savedTo = accounts.findById(toAccount.getAccountId()).orElseThrow();
        List<Transfer> savedTransfers = transfers
                .findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId());
        assertThat(savedFrom.getBalance()).isEqualByComparingTo("70000.0000");
        assertThat(savedTo.getBalance()).isEqualByComparingTo("50000.0000");
        assertThat(savedFrom.getBalance().add(savedTo.getBalance())).isEqualByComparingTo(totalBefore);
        assertThat(savedTransfers).hasSize(1);

        Transfer savedTransfer = savedTransfers.get(0);
        assertThat(savedTransfer.getFromAccount().getAccountId()).isEqualTo(fromAccount.getAccountId());
        assertThat(savedTransfer.getToAccount().getAccountId()).isEqualTo(toAccount.getAccountId());
        assertThat(savedTransfer.getAmount()).isEqualByComparingTo("30000.0000");
        assertThat(savedTransfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(savedTransfer.getCompletedAt()).isNotNull();

        List<LedgerEntry> entries = ledgerEntries
                .findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(savedTransfer.getTransferId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getType)
                .containsExactly(LedgerEntryType.DEBIT, LedgerEntryType.CREDIT);
        assertThat(entries).extracting(LedgerEntry::getAmount)
                .containsExactly(new BigDecimal("-30000.0000"), new BigDecimal("30000.0000"));
        assertThat(entries).extracting(LedgerEntry::getBalanceAfter)
                .containsExactly(new BigDecimal("70000.0000"), new BigDecimal("50000.0000"));
        assertThat(entries).allSatisfy(entry ->
                assertThat(entry.getTransfer().getTransferId()).isEqualTo(savedTransfer.getTransferId()));

        assertThat(data.size()).isEqualTo(7);
        assertThat(data.path("transferId").asLong()).isEqualTo(savedTransfer.getTransferId());
        assertThat(data.path("fromAccountId").asLong()).isEqualTo(fromAccount.getAccountId());
        assertThat(data.path("toAccountNumber").asString()).isEqualTo(toAccount.getAccountNumber());
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("30000.0000");
        assertThat(data.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(data.path("fromBalanceAfter").decimalValue()).isEqualByComparingTo("70000.0000");
        assertThat(data.path("completedAt").isNull()).isFalse();
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("tobalance", "userid", "email", "name", "password", "token");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(recipient.getEmail());
    }

    // 타인 소유 계좌를 출금 계좌로 사용하면 ACCOUNT_001로 숨기고 모든 DB 상태를 유지하는지 검증한다.
    @Test
    void foreignSourceAccountReturnsAccount001WithoutChanges() throws Exception {
        User requester = createUser();
        User owner = createUser();
        Account foreignFrom = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(requester, new BigDecimal("20.0000"), AccountStatus.ACTIVE);

        transfer(accessToken(requester), foreignFrom.getAccountId(), toAccount.getAccountNumber(), "10.0000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_001"));

        assertUnchanged(foreignFrom, "100.0000");
        assertUnchanged(toAccount, "20.0000");
        assertNoTransferOrLedger(foreignFrom, toAccount);
    }

    // 조회된 출금·입금 계좌 ID가 같으면 TRANSFER_001로 거부하고 아무 기록도 남기지 않는지 검증한다.
    @Test
    void sameAccountTransferReturnsTransfer001() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);

        transfer(accessToken(owner), account.getAccountId(), account.getAccountNumber(), "10.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_001"));

        assertUnchanged(account, "100.0000");
        assertNoTransferOrLedger(account, account);
    }

    // 잔액보다 큰 이체를 ACCOUNT_003으로 거부하고 양쪽 잔액과 기록을 유지하는지 검증한다.
    @Test
    void insufficientBalanceReturnsAccount003WithoutChanges() throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("10000.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(createUser(), new BigDecimal("20000.0000"), AccountStatus.ACTIVE);

        transfer(accessToken(owner), fromAccount.getAccountId(), toAccount.getAccountNumber(), "20000.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_003"));

        assertUnchanged(fromAccount, "10000.0000");
        assertUnchanged(toAccount, "20000.0000");
        assertNoTransferOrLedger(fromAccount, toAccount);
    }

    // SUSPENDED·CLOSED 출금 계좌를 ACCOUNT_002로 차단하고 양쪽 잔액을 유지하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void unavailableSourceAccountCannotTransfer(AccountStatus status) throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("100.0000"), status);
        Account toAccount = createAccount(createUser(), new BigDecimal("20.0000"), AccountStatus.ACTIVE);

        transfer(accessToken(owner), fromAccount.getAccountId(), toAccount.getAccountNumber(), "10.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_002"));

        assertUnchanged(fromAccount, "100.0000");
        assertUnchanged(toAccount, "20.0000");
        assertNoTransferOrLedger(fromAccount, toAccount);
    }

    // SUSPENDED·CLOSED 입금 계좌를 ACCOUNT_002로 차단하고 양쪽 잔액을 유지하는지 검증한다.
    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void unavailableRecipientAccountCannotReceiveTransfer(AccountStatus status) throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(createUser(), new BigDecimal("20.0000"), status);

        transfer(accessToken(owner), fromAccount.getAccountId(), toAccount.getAccountNumber(), "10.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_002"));

        assertUnchanged(fromAccount, "100.0000");
        assertUnchanged(toAccount, "20.0000");
        assertNoTransferOrLedger(fromAccount, toAccount);
    }

    // 0·음수·null·scale 초과·precision 초과 금액을 공통 400 오류로 거부하는지 검증한다.
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.0000", "null", "0.00001", "1000000000000000.0000"})
    void invalidAmountsReturnCommon001(String amountLiteral) throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(createUser(), new BigDecimal("20.0000"), AccountStatus.ACTIVE);

        transfer(accessToken(owner), fromAccount.getAccountId(), toAccount.getAccountNumber(), amountLiteral)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        assertUnchanged(fromAccount, "100.0000");
        assertUnchanged(toAccount, "20.0000");
        assertNoTransferOrLedger(fromAccount, toAccount);
    }

    // 없는 출금 계좌와 없는 수취 계좌번호를 동일한 ACCOUNT_001로 반환하는지 검증한다.
    @Test
    void missingAccountsReturnAccount001() throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        String accessToken = accessToken(owner);

        transfer(accessToken, Long.MAX_VALUE, fromAccount.getAccountNumber(), "10.0000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_001"));
        transfer(accessToken, fromAccount.getAccountId(), "missing-account-number", "10.0000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_001"));

        assertUnchanged(fromAccount, "100.0000");
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId())).isEmpty();
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(fromAccount.getAccountId())).isEmpty();
    }

    // 수취 계좌의 입금 후 잔액이 DECIMAL(19,4)를 넘으면 공통 400 오류로 거부하는지 검증한다.
    @Test
    void recipientBalanceOverflowReturnsCommon001() throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("1.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(createUser(), new BigDecimal("999999999999999.9999"),
                AccountStatus.ACTIVE);

        transfer(accessToken(owner), fromAccount.getAccountId(), toAccount.getAccountNumber(), "0.0001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        assertUnchanged(fromAccount, "1.0000");
        assertUnchanged(toAccount, "999999999999999.9999");
        assertNoTransferOrLedger(fromAccount, toAccount);
    }

    // 원장 저장 실패 시 양쪽 잔액과 Transfer·Ledger가 모두 롤백되는지 검증한다.
    @Test
    void ledgerFailureRollsBackEntireTransfer() throws Exception {
        User owner = createUser();
        Account fromAccount = createAccount(owner, new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        Account toAccount = createAccount(createUser(), new BigDecimal("20.0000"), AccountStatus.ACTIVE);
        doThrow(new DataIntegrityViolationException("의도한 이체 원장 저장 실패"))
                .when(ledgerEntries).saveAllAndFlush(any());

        transfer(accessToken(owner), fromAccount.getAccountId(), toAccount.getAccountNumber(), "10.0000")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"));

        assertUnchanged(fromAccount, "100.0000");
        assertUnchanged(toAccount, "20.0000");
        assertNoTransferOrLedger(fromAccount, toAccount);
    }

    // Access Token이 없으면 이체 처리 전에 AUTH_003을 반환하는지 검증한다.
    @Test
    void missingAccessTokenReturnsAuth003() throws Exception {
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":1,\"toAccountNumber\":\"1234\",\"amount\":1.0000}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }

    // 테스트용 ACTIVE 회원을 실제 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "transfer-test-hash",
                "Transfer Test User", UserStatus.ACTIVE));
    }

    // 지정한 잔액과 상태의 테스트 계좌를 실제 MySQL에 저장한다.
    private Account createAccount(User owner, BigDecimal balance, AccountStatus status) {
        return accounts.saveAndFlush(new Account(owner,
                Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()), balance, status));
    }

    // 기존 JWT 발급 정책으로 테스트 회원의 Access Token을 생성한다.
    private String accessToken(User user) {
        return accessTokens.issue(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // 양수 이체 금액을 JSON 숫자로 전달하여 실제 MVC·Security·Transaction 흐름을 호출한다.
    private ResultActions transfer(String accessToken, long fromAccountId, String toAccountNumber,
                                   String amountLiteral) throws Exception {
        return mvc.perform(post("/api/transfers")
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":" + fromAccountId
                        + ",\"toAccountNumber\":\"" + toAccountNumber
                        + "\",\"amount\":" + amountLiteral + "}"));
    }

    // 계좌를 DB에서 다시 조회해 기대 잔액이 유지되는지 검증한다.
    private void assertUnchanged(Account account, String expectedBalance) {
        assertThat(accounts.findById(account.getAccountId()).orElseThrow().getBalance())
                .isEqualByComparingTo(expectedBalance);
    }

    // 실패한 이체의 출금 계좌 기준 Transfer와 양쪽 Ledger가 남지 않았는지 검증한다.
    private void assertNoTransferOrLedger(Account fromAccount, Account toAccount) {
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId())).isEmpty();
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(fromAccount.getAccountId())).isEmpty();
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(toAccount.getAccountId())).isEmpty();
    }
}
