package com.bankingpj.backend.ledger;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(MySqlTestContainerConfiguration.class)
class AccountTransactionIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(93000000000000L);

    @Autowired private AccountService accountService;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntries;

    // 정상 입금 후 계좌 잔액과 CREDIT 원장이 서비스 트랜잭션에서 함께 커밋되는지 검증한다.
    @Test
    void commitsAccountAndCreditLedgerTogether() {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"));

        accountService.deposit(owner.getUserId(), account.getAccountId(), new BigDecimal("25000.0000"));

        assertThat(readBalanceInNewTransaction(account.getAccountId())).isEqualByComparingTo("125000.0000");
        List<LedgerEntry> entries = readLedgerInNewTransaction(account.getAccountId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("25000.0000");
        assertThat(entries.get(0).getBalanceAfter()).isEqualByComparingTo("125000.0000");
    }

    // 정상 출금 후 계좌 잔액과 DEBIT 원장이 서비스 트랜잭션에서 함께 커밋되는지 검증한다.
    @Test
    void commitsAccountAndDebitLedgerTogether() {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"));

        accountService.withdraw(owner.getUserId(), account.getAccountId(), new BigDecimal("30000.0000"));

        assertThat(readBalanceInNewTransaction(account.getAccountId())).isEqualByComparingTo("70000.0000");
        List<LedgerEntry> entries = readLedgerInNewTransaction(account.getAccountId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("-30000.0000");
        assertThat(entries.get(0).getBalanceAfter()).isEqualByComparingTo("70000.0000");
    }

    // CREDIT 원장 저장 예외가 서비스 밖으로 전달되고 앞선 잔액 증가까지 DB에서 롤백되는지 검증한다.
    @Test
    void rollsBackDepositWhenCreditLedgerSaveFails() {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"));
        RuntimeException failure = new RuntimeException("의도한 CREDIT 원장 저장 실패");
        doThrow(failure).when(ledgerEntries).saveAndFlush(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(
                owner.getUserId(), account.getAccountId(), new BigDecimal("50000.0000")))
                .isSameAs(failure);

        assertThat(readBalanceInNewTransaction(account.getAccountId())).isEqualByComparingTo("100000.0000");
        assertThat(readLedgerInNewTransaction(account.getAccountId())).isEmpty();
    }

    // DEBIT 원장 저장 예외가 서비스 밖으로 전달되고 앞선 잔액 감소까지 DB에서 롤백되는지 검증한다.
    @Test
    void rollsBackWithdrawalWhenDebitLedgerSaveFails() {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("100000.0000"));
        RuntimeException failure = new RuntimeException("의도한 DEBIT 원장 저장 실패");
        doThrow(failure).when(ledgerEntries).saveAndFlush(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.withdraw(
                owner.getUserId(), account.getAccountId(), new BigDecimal("30000.0000")))
                .isSameAs(failure);

        assertThat(readBalanceInNewTransaction(account.getAccountId())).isEqualByComparingTo("100000.0000");
        assertThat(readLedgerInNewTransaction(account.getAccountId())).isEmpty();
    }

    // 잔액 부족은 계좌 변경 전에 차단되어 잔액과 원장이 그대로 유지되는지 검증한다.
    @Test
    void insufficientBalanceStopsBeforeAccountAndLedgerChanges() {
        User owner = createUser();
        Account account = createAccount(owner, new BigDecimal("50000.0000"));

        assertThatThrownBy(() -> accountService.withdraw(
                owner.getUserId(), account.getAccountId(), new BigDecimal("70000.0000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        assertThat(readBalanceInNewTransaction(account.getAccountId())).isEqualByComparingTo("50000.0000");
        assertThat(readLedgerInNewTransaction(account.getAccountId())).isEmpty();
    }

    // 트랜잭션 테스트에 사용할 ACTIVE 회원을 실제 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "transaction-test-hash",
                "Transaction Test User", UserStatus.ACTIVE));
    }

    // 지정한 초기 잔액의 ACTIVE 계좌를 실제 MySQL에 저장한다.
    private Account createAccount(User owner, BigDecimal balance) {
        return accounts.saveAndFlush(new Account(owner,
                Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()), balance, AccountStatus.ACTIVE));
    }

    // 기존 영속성 컨텍스트와 분리된 새 트랜잭션에서 실제 DB 잔액을 조회한다.
    private BigDecimal readBalanceInNewTransaction(Long accountId) {
        return inNewTransaction(() -> accounts.findById(accountId).orElseThrow().getBalance());
    }

    // 기존 영속성 컨텍스트와 분리된 새 트랜잭션에서 실제 DB 원장을 조회한다.
    private List<LedgerEntry> readLedgerInNewTransaction(Long accountId) {
        return inNewTransaction(
                () -> ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(accountId));
    }

    // 검증 조회마다 REQUIRES_NEW 트랜잭션을 열어 서비스 트랜잭션 결과와 1차 캐시를 분리한다.
    private <T> T inNewTransaction(Supplier<T> query) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> query.get());
    }
}
