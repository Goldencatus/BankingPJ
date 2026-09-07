package com.bankingpj.backend.transfer;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import com.bankingpj.backend.transfer.service.TransferService;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private UserRepository users;
    @Mock private AccountRepository accounts;
    @Mock private TransferRepository transfers;
    @Mock private LedgerEntryRepository ledgerEntries;
    private TransferService service;

    // 격리된 이체 서비스에 회원·계좌·Transfer·Ledger 저장소 대역을 연결한다.
    @BeforeEach
    void setUp() {
        service = new TransferService(users, accounts, transfers, ledgerEntries);
    }

    // 큰 ID 계좌에서 작은 ID 계좌로 이체해도 작은 행부터 잠그는지 검증한다.
    @Test
    void locksAccountsInAscendingIdOrderForReverseDirectionTransfer() {
        User owner = user(1L, "lock-owner@example.com");
        Account fromAccount = account(20L, owner, "98000000000020", "100.0000");
        Account toAccount = account(10L, user(2L, "lock-recipient@example.com"),
                "98000000000010", "20.0000");
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(accounts.findAccountIdByAccountNumber(toAccount.getAccountNumber())).thenReturn(Optional.of(10L));
        when(accounts.findByIdForUpdate(10L)).thenReturn(Optional.of(toAccount));
        when(accounts.findByIdForUpdate(20L)).thenReturn(Optional.of(fromAccount));

        service.transfer(1L, 20L, toAccount.getAccountNumber(), new BigDecimal("10.0000"));

        InOrder lockOrder = inOrder(accounts);
        lockOrder.verify(accounts).findByIdForUpdate(10L);
        lockOrder.verify(accounts).findByIdForUpdate(20L);
        assertThat(fromAccount.getBalance()).isEqualByComparingTo("90.0000");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("30.0000");
    }

    // Lock 순서 테스트에 사용할 ID가 설정된 ACTIVE 회원을 생성한다.
    private User user(Long userId, String email) {
        User user = new User(email, "hash", "Lock Test User", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    // Lock 순서 테스트에 사용할 ID와 잔액이 설정된 ACTIVE 계좌를 생성한다.
    private Account account(Long accountId, User owner, String accountNumber, String balance) {
        Account account = new Account(owner, accountNumber, new BigDecimal(balance), AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "accountId", accountId);
        return account;
    }
}
