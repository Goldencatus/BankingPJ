package com.bankingpj.backend.transfer;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.transfer.dto.TransferResponse;
import com.bankingpj.backend.transfer.repository.TransferIdempotencyRepository;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import com.bankingpj.backend.transfer.service.IdempotentTransferService;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@Import(MySqlTestContainerConfiguration.class)
class TransferIdempotencyRollbackIntegrationTest {

    private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong(99500000000000L);

    @Autowired private IdempotentTransferService transferService;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private TransferRepository transfers;
    @Autowired private TransferIdempotencyRepository idempotencies;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntries;

    // 멱등성 선점 후 이체가 실패하면 전부 롤백되고 같은 키의 정상 재시도가 성공하는지 검증한다.
    @Test
    void failedTransferRollsBackIdempotencyAndAllowsRetry() {
        User sender = createUser();
        Account fromAccount = createAccount(sender, "100000.0000");
        Account toAccount = createAccount(createUser(), "20000.0000");
        String key = UUID.randomUUID().toString();
        RuntimeException failure = new RuntimeException("의도한 멱등성 이체 원장 실패");
        doThrow(failure).when(ledgerEntries).saveAllAndFlush(any());

        assertThatThrownBy(() -> transferService.transfer(sender.getUserId(), key, fromAccount.getAccountId(),
                toAccount.getAccountNumber(), new BigDecimal("30000.0000"))).isSameAs(failure);

        assertThat(readBalance(fromAccount)).isEqualByComparingTo("100000.0000");
        assertThat(readBalance(toAccount)).isEqualByComparingTo("20000.0000");
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId())).isEmpty();
        assertThat(ledgerEntries.findAllByAccount_AccountIdOrderByLedgerEntryIdAsc(fromAccount.getAccountId())).isEmpty();
        assertThat(idempotencies.countByUserIdAndIdempotencyKey(sender.getUserId(), key)).isZero();

        reset(ledgerEntries);
        TransferResponse retried = transferService.transfer(sender.getUserId(), key, fromAccount.getAccountId(),
                toAccount.getAccountNumber(), new BigDecimal("30000.0000"));

        assertThat(readBalance(fromAccount)).isEqualByComparingTo("70000.0000");
        assertThat(readBalance(toAccount)).isEqualByComparingTo("50000.0000");
        assertThat(transfers.findAllByFromAccount_AccountIdOrderByTransferIdAsc(fromAccount.getAccountId()))
                .hasSize(1);
        assertThat(ledgerEntries.findAllByTransfer_TransferIdOrderByLedgerEntryIdAsc(retried.transferId())).hasSize(2);
        assertThat(idempotencies.countByUserIdAndIdempotencyKey(sender.getUserId(), key)).isOne();
    }

    // Rollback 테스트용 ACTIVE 회원을 실제 MySQL에 저장한다.
    private User createUser() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "idempotency-rollback-hash",
                "Idempotency Rollback User", UserStatus.ACTIVE));
    }

    // Rollback 테스트용 ACTIVE 계좌를 지정한 잔액으로 저장한다.
    private Account createAccount(User owner, String balance) {
        return accounts.saveAndFlush(new Account(owner, Long.toString(ACCOUNT_SEQUENCE.incrementAndGet()),
                new BigDecimal(balance), AccountStatus.ACTIVE));
    }

    // 영속성 컨텍스트의 변경을 DB 조회로 확인할 수 있도록 계좌 잔액을 다시 읽는다.
    private BigDecimal readBalance(Account account) {
        return accounts.findById(account.getAccountId()).orElseThrow().getBalance();
    }
}
