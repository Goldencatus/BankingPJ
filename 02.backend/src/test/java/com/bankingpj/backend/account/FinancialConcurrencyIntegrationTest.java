package com.bankingpj.backend.account;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import com.bankingpj.backend.transfer.service.TransferService;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainerConfiguration.class)
class FinancialConcurrencyIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(97000000000000L);

    @Autowired private AccountService accountService;
    @Autowired private TransferService transferService;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerEntryRepository ledgerEntries;
    @Autowired private TransferRepository transfers;
    @Autowired private DataSource dataSource;

    // 동일 계좌의 동시 출금 100건을 모두 반영하여 Lost Update 없이 잔액과 원장을 맞추는지 검증한다.
    @Test
    void serializesOneHundredConcurrentWithdrawals() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("1000000.0000"));
        int requestCount = 100;
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger completedCount = new AtomicInteger();
        int hikariMaximumPoolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        System.out.printf("[CONCURRENCY] scenario=withdraw initialBalance=%s requests=%d hikariMaximumPoolSize=%d%n",
                account.getBalance().toPlainString(), requestCount, hikariMaximumPoolSize);

        List<AttemptResult> results = runConcurrently(requestCount, requestNumber -> () -> {
            String threadName = Thread.currentThread().getName();
            System.out.printf("request=%d thread=%s START%n", requestNumber, threadName);
            try {
                accountService.withdraw(owner.getUserId(), account.getAccountId(), new BigDecimal("10000.0000"));
                successCount.incrementAndGet();
                int completed = completedCount.incrementAndGet();
                System.out.printf("request=%d thread=%s SUCCESS completed=%d/%d%n",
                        requestNumber, threadName, completed, requestCount);
                return AttemptResult.SUCCESS;
            } catch (BusinessException exception) {
                failureCount.incrementAndGet();
                int completed = completedCount.incrementAndGet();
                System.out.printf("request=%d thread=%s BUSINESS_FAILURE code=%s completed=%d/%d%n",
                        requestNumber, threadName, exception.getErrorCode().getCode(), completed, requestCount);
                return AttemptResult.INSUFFICIENT_BALANCE;
            }
        });

        BigDecimal finalBalance = readBalance(account.getAccountId());
        var savedLedgerEntries = ledgerEntries
                .findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId());
        boolean pass = successCount.get() == requestCount
                && failureCount.get() == 0
                && finalBalance.compareTo(new BigDecimal("0.0000")) == 0
                && savedLedgerEntries.size() == requestCount;
        System.out.printf("[RESULT]%nsuccess=%d%nfailed=%d%nfinalBalance=%s%nledgerCount=%d%n"
                        + "PASS=%s condition=success==%d && failed==0 && balance==0.0000 && ledger==%d%n",
                successCount.get(), failureCount.get(), finalBalance.toPlainString(), savedLedgerEntries.size(),
                pass, requestCount, requestCount);

        assertThat(results).containsOnly(AttemptResult.SUCCESS).hasSize(requestCount);
        assertThat(finalBalance).isEqualByComparingTo("0.0000");
        assertThat(savedLedgerEntries)
                .hasSize(requestCount)
                .allSatisfy(entry -> {
                    assertThat(entry.getType()).isEqualTo(LedgerEntryType.DEBIT);
                    assertThat(entry.getAmount()).isEqualByComparingTo("-10000.0000");
                });
        assertThat(pass).isTrue();
    }

    // 잔액보다 많은 동시 출금에서 정확히 10건만 성공하고 잔액이 음수가 되지 않는지 검증한다.
    @Test
    void allowsOnlyAffordableConcurrentWithdrawals() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"));

        List<AttemptResult> results = runConcurrently(20, requestNumber -> () -> {
            try {
                accountService.withdraw(owner.getUserId(), account.getAccountId(), new BigDecimal("10000.0000"));
                return AttemptResult.SUCCESS;
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.INSUFFICIENT_BALANCE) {
                    return AttemptResult.INSUFFICIENT_BALANCE;
                }
                throw exception;
            }
        });

        assertThat(results).filteredOn(AttemptResult.SUCCESS::equals).hasSize(10);
        assertThat(results).filteredOn(AttemptResult.INSUFFICIENT_BALANCE::equals).hasSize(10);
        assertThat(readBalance(account.getAccountId())).isEqualByComparingTo("0.0000");
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId()))
                .hasSize(10)
                .allSatisfy(entry -> assertThat(entry.getAmount()).isEqualByComparingTo("-10000.0000"));
    }

    // 동일 계좌의 동시 입금 50건을 모두 반영하여 최종 잔액과 CREDIT 원장 수를 검증한다.
    @Test
    void preservesAllConcurrentDeposits() throws Exception {
        User owner = createUser();
        Account account = createAccount(owner, BigDecimal.ZERO.setScale(4));

        List<AttemptResult> results = runConcurrently(50, requestNumber -> () -> {
            accountService.deposit(owner.getUserId(), account.getAccountId(), new BigDecimal("10000.0000"));
            return AttemptResult.SUCCESS;
        });

        assertThat(results).containsOnly(AttemptResult.SUCCESS).hasSize(50);
        assertThat(readBalance(account.getAccountId())).isEqualByComparingTo("500000.0000");
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(account.getAccountId()))
                .hasSize(50)
                .allSatisfy(entry -> {
                    assertThat(entry.getType()).isEqualTo(LedgerEntryType.CREDIT);
                    assertThat(entry.getAmount()).isEqualByComparingTo("10000.0000");
                });
    }

    // 반대 방향 이체 두 건이 제한 시간 안에 완료되고 총액·Transfer·Ledger 정합성을 유지하는지 검증한다.
    @Test
    void completesOppositeDirectionTransfersWithoutDeadlock() throws Exception {
        User owner = createUser();
        Account firstAccount = createAccount(owner, new BigDecimal("100000.0000"));
        Account secondAccount = createAccount(owner, new BigDecimal("100000.0000"));
        assertThat(firstAccount.getAccountId()).isLessThan(secondAccount.getAccountId());

        List<Callable<AttemptResult>> actions = List.of(
                () -> {
                    transferService.transfer(owner.getUserId(), firstAccount.getAccountId(),
                            secondAccount.getAccountNumber(), new BigDecimal("10000.0000"));
                    return AttemptResult.SUCCESS;
                },
                () -> {
                    transferService.transfer(owner.getUserId(), secondAccount.getAccountId(),
                            firstAccount.getAccountNumber(), new BigDecimal("20000.0000"));
                    return AttemptResult.SUCCESS;
                });

        List<AttemptResult> results = runConcurrently(actions);

        BigDecimal firstBalance = readBalance(firstAccount.getAccountId());
        BigDecimal secondBalance = readBalance(secondAccount.getAccountId());
        assertThat(results).containsOnly(AttemptResult.SUCCESS).hasSize(2);
        assertThat(firstBalance).isEqualByComparingTo("110000.0000");
        assertThat(secondBalance).isEqualByComparingTo("90000.0000");
        assertThat(firstBalance.add(secondBalance)).isEqualByComparingTo("200000.0000");
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(firstAccount.getAccountId()))
                .hasSize(1);
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(secondAccount.getAccountId()))
                .hasSize(1);
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(firstAccount.getAccountId()))
                .hasSize(2);
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(secondAccount.getAccountId()))
                .hasSize(2);
    }

    // 같은 작업을 지정된 수만큼 동시에 시작하고 모든 결과를 제한 시간 안에 수집한다.
    private List<AttemptResult> runConcurrently(
            int taskCount, IntFunction<Callable<AttemptResult>> actionFactory) throws Exception {
        List<Callable<AttemptResult>> actions = new ArrayList<>(taskCount);
        for (int index = 0; index < taskCount; index++) {
            actions.add(actionFactory.apply(index + 1));
        }
        return runConcurrently(actions);
    }

    // 서로 다른 작업도 같은 시작 신호로 실행하여 실제 DB Transaction 경쟁을 만든다.
    private List<AttemptResult> runConcurrently(List<Callable<AttemptResult>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        List<Future<AttemptResult>> futures = new ArrayList<>(actions.size());
        try {
            for (Callable<AttemptResult> action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start timeout");
                    }
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<AttemptResult> results = new ArrayList<>(actions.size());
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // 동시성 테스트에 사용할 ACTIVE 회원을 실제 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "concurrency-test-hash",
                "Concurrency Test User", UserStatus.ACTIVE));
    }

    // 지정한 잔액의 ACTIVE 계좌를 실제 MySQL에 저장한다.
    private Account createAccount(User owner, BigDecimal balance) {
        return accounts.saveAndFlush(new Account(owner,
                Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()), balance, AccountStatus.ACTIVE));
    }

    // 모든 작업 종료 후 Repository의 새 조회로 실제 DB 잔액을 반환한다.
    private BigDecimal readBalance(Long accountId) {
        return accounts.findById(accountId).orElseThrow().getBalance();
    }

    private enum AttemptResult {
        SUCCESS,
        INSUFFICIENT_BALANCE
    }
}
