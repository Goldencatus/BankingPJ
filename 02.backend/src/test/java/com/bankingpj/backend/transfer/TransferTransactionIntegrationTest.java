package com.bankingpj.backend.transfer;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import com.bankingpj.backend.transfer.service.TransferService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(MySqlTestContainerConfiguration.class)
class TransferTransactionIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(96000000000000L);

    @Autowired private TransferService transferService;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private TransferRepository transfers;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntries;

    // 양쪽 잔액과 Transfer 처리 후 Ledger 저장 예외가 발생하면 전체 변경이 롤백되는지 검증한다.
    @Test
    void rollsBackBalancesAndTransferWhenLedgerBatchSaveFails() {
        User sender = createUser();
        Account fromAccount = createAccount(sender, new BigDecimal("100000.0000"));
        Account toAccount = createAccount(createUser(), new BigDecimal("20000.0000"));
        BigDecimal totalBefore = fromAccount.getBalance().add(toAccount.getBalance());
        RuntimeException failure = new RuntimeException("의도한 이체 Ledger 저장 실패");
        doThrow(failure).when(ledgerEntries).saveAllAndFlush(any());

        assertThatThrownBy(() -> transferService.transfer(sender.getUserId(), fromAccount.getAccountId(),
                toAccount.getAccountNumber(), new BigDecimal("30000.0000"))).isSameAs(failure);

        TransferDatabaseState state = readStateInNewTransaction(fromAccount.getAccountId(), toAccount.getAccountId());
        assertThat(state.fromBalance()).isEqualByComparingTo("100000.0000");
        assertThat(state.toBalance()).isEqualByComparingTo("20000.0000");
        assertThat(state.fromBalance().add(state.toBalance())).isEqualByComparingTo(totalBefore);
        assertThat(state.transferCount()).isZero();
        assertThat(state.ledgerCount()).isZero();
    }

    // DEBIT 원장을 실제 flush한 뒤 CREDIT 단계에서 실패해도 선행 INSERT까지 롤백되는지 검증한다.
    @Test
    void rollsBackFlushedDebitWhenCreditLedgerSaveFails() {
        User sender = createUser();
        Account fromAccount = createAccount(sender, new BigDecimal("100000.0000"));
        Account toAccount = createAccount(createUser(), new BigDecimal("20000.0000"));
        BigDecimal totalBefore = fromAccount.getBalance().add(toAccount.getBalance());
        RuntimeException failure = new RuntimeException("의도한 CREDIT 원장 저장 실패");
        AtomicBoolean debitFlushedBeforeFailure = new AtomicBoolean(false);
        doAnswer(invocation -> {
            Iterable<?> requestedEntries = invocation.getArgument(0);
            LedgerEntry debitEntry = (LedgerEntry) requestedEntries.iterator().next();
            ledgerEntries.saveAndFlush(debitEntry);
            debitFlushedBeforeFailure.set(true);
            throw failure;
        }).when(ledgerEntries).saveAllAndFlush(any());

        assertThatThrownBy(() -> transferService.transfer(sender.getUserId(), fromAccount.getAccountId(),
                toAccount.getAccountNumber(), new BigDecimal("30000.0000"))).isSameAs(failure);
        assertThat(debitFlushedBeforeFailure).isTrue();

        TransferDatabaseState state = readStateInNewTransaction(fromAccount.getAccountId(), toAccount.getAccountId());
        assertThat(state.fromBalance()).isEqualByComparingTo("100000.0000");
        assertThat(state.toBalance()).isEqualByComparingTo("20000.0000");
        assertThat(state.fromBalance().add(state.toBalance())).isEqualByComparingTo(totalBefore);
        assertThat(state.transferCount()).isZero();
        assertThat(state.ledgerCount()).isZero();
    }

    // 트랜잭션 테스트에 사용할 ACTIVE 회원을 실제 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(java.util.UUID.randomUUID() + "@example.com", "transfer-transaction-hash",
                "Transfer Transaction User", UserStatus.ACTIVE));
    }

    // 지정한 초기 잔액의 ACTIVE 계좌를 실제 MySQL에 저장한다.
    private Account createAccount(User owner, BigDecimal balance) {
        return accounts.saveAndFlush(new Account(owner,
                Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()), balance, AccountStatus.ACTIVE));
    }

    // 서비스 영속성 컨텍스트와 분리한 새 트랜잭션에서 양쪽 잔액과 기록 개수를 조회한다.
    private TransferDatabaseState readStateInNewTransaction(Long fromAccountId, Long toAccountId) {
        return inNewTransaction(() -> new TransferDatabaseState(
                accounts.findById(fromAccountId).orElseThrow().getBalance(),
                accounts.findById(toAccountId).orElseThrow().getBalance(),
                transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccountId).size(),
                ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(fromAccountId).size()
                        + ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(toAccountId).size()));
    }

    // 검증 조회마다 REQUIRES_NEW 트랜잭션을 열어 실패한 서비스 트랜잭션과 1차 캐시를 분리한다.
    private <T> T inNewTransaction(Supplier<T> query) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> query.get());
    }

    private record TransferDatabaseState(
            BigDecimal fromBalance,
            BigDecimal toBalance,
            int transferCount,
            int ledgerCount
    ) {
    }
}
