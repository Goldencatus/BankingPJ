package com.bankingpj.backend.transfer;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.transfer.domain.TransferIdempotency;
import com.bankingpj.backend.transfer.domain.TransferIdempotencyStatus;
import com.bankingpj.backend.transfer.repository.TransferIdempotencyRepository;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class TransferIdempotencyIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(99000000000000L);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AccessTokenIssuer accessTokens;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private AccountService accountService;
    @Autowired private TransferRepository transfers;
    @Autowired private LedgerEntryRepository ledgerEntries;
    @Autowired private TransferIdempotencyRepository idempotencies;

    // 최초 이체가 잔액·Transfer·Ledger와 COMPLETED 멱등성 행을 함께 저장하는지 검증한다.
    @Test
    void firstRequestCompletesTransferAndIdempotency() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");
        String key = UUID.randomUUID().toString();

        long transferId = transferId(transfer(accessToken(sender), key, fromAccount.getAccountId(),
                toAccount.getAccountNumber(), "30000.0000").andExpect(status().isOk()).andReturn());

        TransferIdempotency saved = idempotencies.findByUserIdAndIdempotencyKey(sender.getUserId(), key)
                .orElseThrow();
        assertThat(readBalance(fromAccount)).isEqualByComparingTo("70000.0000");
        assertThat(readBalance(toAccount)).isEqualByComparingTo("50000.0000");
        assertThat(transfers.findById(transferId)).isPresent();
        assertThat(ledgerEntries.findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(transferId)).hasSize(2);
        assertThat(saved.getStatus()).isEqualTo(TransferIdempotencyStatus.COMPLETED);
        assertThat(saved.getTransfer().getTransferId()).isEqualTo(transferId);
        assertThat(saved.getRequestFingerprint()).hasSize(64);
    }

    // 표현만 다른 동일 금액을 포함해 같은 요청 10회가 하나의 기존 이체 결과를 반환하는지 검증한다.
    @Test
    void sequentialDuplicatesReplayOneTransfer() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");
        String key = UUID.randomUUID().toString();
        String token = accessToken(sender);
        List<Long> transferIds = new ArrayList<>();

        for (int request = 0; request < 10; request++) {
            String amount = switch (request % 3) {
                case 0 -> "30000";
                case 1 -> "30000.0";
                default -> "30000.0000";
            };
            transferIds.add(transferId(transfer(token, key, fromAccount.getAccountId(),
                    toAccount.getAccountNumber(), amount).andExpect(status().isOk()).andReturn()));
        }

        assertSingleExecution(sender, key, fromAccount, toAccount, transferIds);
    }

    // 이후 거래로 현재 잔액이 바뀌어도 재응답은 최초 DEBIT 원장의 이체 직후 잔액을 사용하는지 검증한다.
    @Test
    void replayUsesHistoricalLedgerBalance() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");
        String key = UUID.randomUUID().toString();
        String token = accessToken(sender);

        MvcResult original = transfer(token, key, fromAccount.getAccountId(), toAccount.getAccountNumber(),
                "30000.0000").andExpect(status().isOk()).andReturn();
        accountService.deposit(sender.getUserId(), fromAccount.getAccountId(), new BigDecimal("10000.0000"));
        MvcResult replay = transfer(token, key, fromAccount.getAccountId(), toAccount.getAccountNumber(),
                "30000.0000").andExpect(status().isOk()).andReturn();

        assertThat(transferId(replay)).isEqualTo(transferId(original));
        assertThat(mapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("fromBalanceAfter").decimalValue()).isEqualByComparingTo("70000.0000");
        assertThat(readBalance(fromAccount)).isEqualByComparingTo("80000.0000");
    }

    // 같은 키를 다른 금액에 재사용하면 409를 반환하고 추가 금융 변경을 만들지 않는지 검증한다.
    @Test
    void sameKeyWithDifferentAmountReturnsConflict() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");
        String key = UUID.randomUUID().toString();
        String token = accessToken(sender);

        long transferId = transferId(transfer(token, key, fromAccount.getAccountId(),
                toAccount.getAccountNumber(), "30000.0000").andExpect(status().isOk()).andReturn());
        transfer(token, key, fromAccount.getAccountId(), toAccount.getAccountNumber(), "50000.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TRANSFER_002"));

        assertOnlyOneFinancialEffect(sender, key, fromAccount, toAccount, transferId);
    }

    // 같은 키를 다른 수취 계좌에 재사용하면 409를 반환하고 최초 수취 계좌만 변경되는지 검증한다.
    @Test
    void sameKeyWithDifferentRecipientReturnsConflict() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account firstRecipient = createAccount(createUser(), "20000.0000");
        Account secondRecipient = createAccount(createUser(), "40000.0000");
        String key = UUID.randomUUID().toString();
        String token = accessToken(sender);

        long transferId = transferId(transfer(token, key, fromAccount.getAccountId(),
                firstRecipient.getAccountNumber(), "30000.0000").andExpect(status().isOk()).andReturn());
        transfer(token, key, fromAccount.getAccountId(), secondRecipient.getAccountNumber(), "30000.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_002"));

        assertOnlyOneFinancialEffect(sender, key, fromAccount, firstRecipient, transferId);
        assertThat(readBalance(secondRecipient)).isEqualByComparingTo("40000.0000");
    }

    // 동일 API 요청 10개를 동시에 시작해 DB UNIQUE가 하나의 이체 결과로 수렴시키는지 검증한다.
    @Test
    void concurrentDuplicatesReturnOneTransfer() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");
        String key = UUID.randomUUID().toString();
        String token = accessToken(sender);
        int requestCount = 10;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<Long>> futures = new ArrayList<>();

        System.out.printf("[IDEMPOTENCY] concurrent duplicate test START key=%s requests=%d%n",
                maskKey(key), requestCount);
        try {
            for (int index = 1; index <= requestCount; index++) {
                int requestNumber = index;
                futures.add(executor.submit(() -> executeConcurrentRequest(requestNumber, requestCount,
                        ready, start, completed, token, key, fromAccount, toAccount)));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Long> transferIds = new ArrayList<>();
            for (Future<Long> future : futures) {
                transferIds.add(future.get(60, TimeUnit.SECONDS));
            }
            long transferId = transferIds.get(0);
            int uniqueTransferIds = new HashSet<>(transferIds).size();
            int transferCount = transfers
                    .findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId()).size();
            int ledgerCount = ledgerEntries
                    .findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(transferId).size();
            long idempotencyCount = idempotencies
                    .countByUserIdAndIdempotencyKey(sender.getUserId(), key);
            boolean pass = uniqueTransferIds == 1 && transferCount == 1 && ledgerCount == 2
                    && idempotencyCount == 1 && readBalance(fromAccount).compareTo(new BigDecimal("70000.0000")) == 0
                    && readBalance(toAccount).compareTo(new BigDecimal("50000.0000")) == 0;
            System.out.printf("[IDEMPOTENCY] RESULT requests=%d uniqueTransferIds=%d transfers=%d "
                            + "ledgerEntries=%d idempotencyRows=%d balanceMoved=%d PASS=%s%n",
                    requestCount, uniqueTransferIds, transferCount, ledgerCount, idempotencyCount, 1, pass);

            assertThat(transferIds).containsOnly(transferId).hasSize(requestCount);
            assertThat(pass).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // 서로 다른 회원은 같은 문자열 키를 각각 한 번씩 독립적으로 사용할 수 있는지 검증한다.
    @Test
    void sameKeyIsScopedByAuthenticatedUser() throws Exception {
        User firstSender = createUser();
        User secondSender = createUser();
        Account firstFrom = createAccount(firstSender, "100000.0000");
        Account firstTo = createAccount(createUser(), "20000.0000");
        Account secondFrom = createAccount(secondSender, "80000.0000");
        Account secondTo = createAccount(createUser(), "10000.0000");
        String sharedKey = UUID.randomUUID().toString();

        long firstTransferId = transferId(transfer(accessToken(firstSender), sharedKey, firstFrom.getAccountId(),
                firstTo.getAccountNumber(), "30000.0000").andExpect(status().isOk()).andReturn());
        long secondTransferId = transferId(transfer(accessToken(secondSender), sharedKey, secondFrom.getAccountId(),
                secondTo.getAccountNumber(), "20000.0000").andExpect(status().isOk()).andReturn());

        assertThat(firstTransferId).isNotEqualTo(secondTransferId);
        assertThat(idempotencies.countByUserIdAndIdempotencyKey(firstSender.getUserId(), sharedKey)).isOne();
        assertThat(idempotencies.countByUserIdAndIdempotencyKey(secondSender.getUserId(), sharedKey)).isOne();
        assertThat(readBalance(firstFrom)).isEqualByComparingTo("70000.0000");
        assertThat(readBalance(secondFrom)).isEqualByComparingTo("60000.0000");
    }

    // 필수 Idempotency-Key 헤더가 없으면 금융 처리 전에 COMMON_001로 거부하는지 검증한다.
    @Test
    void missingIdempotencyKeyReturnsBadRequest() throws Exception {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");

        mvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + accessToken(sender))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(fromAccount.getAccountId(), toAccount.getAccountNumber(), "30000.0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId())).isEmpty();
    }

    // 동시 요청 하나를 실제 MVC로 실행하고 요청별 Thread와 동일 결과를 관찰 로그로 남긴다.
    private long executeConcurrentRequest(int requestNumber, int requestCount, CountDownLatch ready,
                                          CountDownLatch start, AtomicInteger completed, String token, String key,
                                          Account fromAccount, Account toAccount) throws Exception {
        String thread = Thread.currentThread().getName();
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Idempotency concurrent start timeout");
        }
        System.out.printf("[IDEMPOTENCY] request=%d thread=%s START%n", requestNumber, thread);
        long transferId = transferId(transfer(token, key, fromAccount.getAccountId(),
                toAccount.getAccountNumber(), "30000.0000").andExpect(status().isOk()).andReturn());
        System.out.printf("[IDEMPOTENCY] request=%d thread=%s SUCCESS transferId=%d completed=%d/%d%n",
                requestNumber, thread, transferId, completed.incrementAndGet(), requestCount);
        return transferId;
    }

    // 순차 재요청이 한 번의 금융 변경과 동일 transferId만 만들었는지 검증한다.
    private void assertSingleExecution(User sender, String key, Account fromAccount, Account toAccount,
                                       List<Long> transferIds) {
        long transferId = transferIds.get(0);
        assertThat(transferIds).containsOnly(transferId).hasSize(10);
        assertOnlyOneFinancialEffect(sender, key, fromAccount, toAccount, transferId);
    }

    // 한 멱등성 키가 Transfer 1건·Ledger 2건·잔액 이동 1회만 만들었는지 검증한다.
    private void assertOnlyOneFinancialEffect(User sender, String key, Account fromAccount,
                                              Account toAccount, long transferId) {
        assertThat(readBalance(fromAccount)).isEqualByComparingTo("70000.0000");
        assertThat(readBalance(toAccount)).isEqualByComparingTo("50000.0000");
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId()))
                .hasSize(1);
        assertThat(ledgerEntries.findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(transferId)).hasSize(2);
        assertThat(idempotencies.countByUserIdAndIdempotencyKey(sender.getUserId(), key)).isOne();
    }

    // 테스트용 ACTIVE 회원을 실제 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "idempotency-test-hash",
                "Idempotency Test User", UserStatus.ACTIVE));
    }

    // 테스트용 ACTIVE 계좌를 지정한 잔액으로 실제 MySQL에 저장한다.
    private Account createAccount(User owner, String balance) {
        return accounts.saveAndFlush(new Account(owner, Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()),
                new BigDecimal(balance), AccountStatus.ACTIVE));
    }

    // 기존 JWT 발급 정책으로 테스트 회원의 Access Token을 생성한다.
    private String accessToken(User user) {
        return accessTokens.issue(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    // 멱등성 키를 포함한 실제 MVC 이체 요청을 생성한다.
    private ResultActions transfer(String token, String key, long fromAccountId,
                                   String toAccountNumber, String amount) throws Exception {
        return mvc.perform(post("/api/transfers")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(fromAccountId, toAccountNumber, amount)));
    }

    // 테스트 이체 요청의 JSON 본문을 생성한다.
    private String requestJson(long fromAccountId, String toAccountNumber, String amount) {
        return "{\"fromAccountId\":" + fromAccountId + ",\"toAccountNumber\":\""
                + toAccountNumber + "\",\"amount\":" + amount + "}";
    }

    // 공통 API 응답에서 완료된 이체 식별자를 읽는다.
    private long transferId(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("transferId").asLong();
    }

    // 계좌를 새로 조회해 실제 DB 잔액을 반환한다.
    private BigDecimal readBalance(Account account) {
        return accounts.findById(account.getAccountId()).orElseThrow().getBalance();
    }

    // 테스트 출력에서 전체 멱등성 키를 숨기고 식별 가능한 앞부분만 남긴다.
    private String maskKey(String key) {
        return key.substring(0, Math.min(8, key.length())) + "***";
    }
}
