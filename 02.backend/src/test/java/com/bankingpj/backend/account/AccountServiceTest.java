package com.bankingpj.backend.account;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.account.service.AccountNumberGenerator;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.dto.DepositResponse;
import com.bankingpj.backend.ledger.dto.WithdrawalResponse;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private UserRepository users;
    @Mock private AccountRepository accounts;
    @Mock private AccountNumberGenerator accountNumbers;
    @Mock private LedgerEntryRepository ledgerEntries;
    private AccountService service;

    // 격리된 계좌 생성 서비스에 저장소와 번호 생성기 대역을 연결한다.
    @BeforeEach
    void setUp() {
        service = new AccountService(users, accounts, accountNumbers, ledgerEntries);
    }

    // 번호 충돌 시 다음 후보를 사용하고 초기 잔액·상태를 서버 정책으로 고정하는지 검증한다.
    @Test
    void retriesCollisionAndUsesServerControlledInitialValues() {
        User user = new User("active@example.com", "hash", "Active User", UserStatus.ACTIVE);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(accountNumbers.generate()).thenReturn("11111111111111", "22222222222222");
        when(accounts.existsByAccountNumber("11111111111111")).thenReturn(true);
        when(accounts.existsByAccountNumber("22222222222222")).thenReturn(false);
        when(accounts.saveAndFlush(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(1L);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accounts).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getAccountNumber()).isEqualTo("22222222222222");
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(captor.getValue().getBalance().scale()).isEqualTo(4);
        assertThat(captor.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    // JWT 회원이 DB에 없으면 기존 인증 오류를 반환하고 계좌를 저장하지 않는지 검증한다.
    @Test
    void missingUserCannotCreateAccount() {
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(99L)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACCESS_TOKEN));
        verify(accounts, never()).saveAndFlush(any());
    }

    // ACTIVE가 아닌 회원은 번호 생성이나 계좌 저장으로 진행하지 않는지 검증한다.
    @Test
    void inactiveUserCannotCreateAccount() {
        User user = new User("inactive@example.com", "hash", "Inactive User", UserStatus.SUSPENDED);
        when(users.findById(2L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.create(2L)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LOGIN_NOT_ALLOWED));
        verify(accountNumbers, never()).generate();
        verify(accounts, never()).saveAndFlush(any());
    }

    // 모든 후보가 충돌하면 10회에서 중단하고 계좌를 저장하지 않는지 검증한다.
    @Test
    void stopsAfterLimitedAccountNumberCollisions() {
        User user = new User("active@example.com", "hash", "Active User", UserStatus.ACTIVE);
        when(users.findById(3L)).thenReturn(Optional.of(user));
        when(accountNumbers.generate()).thenReturn("33333333333333");
        when(accounts.existsByAccountNumber("33333333333333")).thenReturn(true);

        assertThatThrownBy(() -> service.create(3L)).isInstanceOf(IllegalStateException.class);
        verify(accountNumbers, times(10)).generate();
        verify(accounts, never()).saveAndFlush(any());
    }

    // 본인 ACTIVE 계좌 입금 시 잔액과 CREDIT 원장이 같은 금액으로 갱신되는지 검증한다.
    @Test
    void depositsAndCreatesCreditLedgerEntry() {
        User user = new User("deposit@example.com", "hash", "Deposit User", UserStatus.ACTIVE);
        Account account = new Account(user, "44444444444444", new BigDecimal("10.0000"), AccountStatus.ACTIVE);
        when(users.findById(4L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(40L, 4L)).thenReturn(Optional.of(account));
        when(ledgerEntries.saveAndFlush(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DepositResponse response = service.deposit(4L, 40L, new BigDecimal("2.5000"));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntries).saveAndFlush(captor.capture());
        assertThat(account.getBalance()).isEqualByComparingTo("12.5000");
        assertThat(captor.getValue().getType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("2.5000");
        assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo("12.5000");
        assertThat(response.balanceAfter()).isEqualByComparingTo("12.5000");
    }

    // 본인 소유가 아닌 계좌는 존재 여부를 구분하지 않고 ACCOUNT_001로 거부하는지 검증한다.
    @Test
    void foreignOrMissingAccountCannotReceiveDeposit() {
        User user = new User("owner@example.com", "hash", "Owner", UserStatus.ACTIVE);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(50L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deposit(5L, 50L, new BigDecimal("1.0000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        verify(ledgerEntries, never()).saveAndFlush(any());
    }

    // ACTIVE가 아닌 계좌는 ACCOUNT_002로 거부하고 잔액과 원장을 변경하지 않는지 검증한다.
    @Test
    void unavailableAccountCannotReceiveDeposit() {
        User user = new User("suspended-account@example.com", "hash", "Owner", UserStatus.ACTIVE);
        Account account = new Account(user, "55555555555555", new BigDecimal("10.0000"),
                AccountStatus.SUSPENDED);
        when(users.findById(6L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(60L, 6L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.deposit(6L, 60L, new BigDecimal("1.0000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_AVAILABLE));
        assertThat(account.getBalance()).isEqualByComparingTo("10.0000");
        verify(ledgerEntries, never()).saveAndFlush(any());
    }

    // 입금 후 잔액이 DECIMAL(19,4) 범위를 넘으면 COMMON_001로 저장 전에 거부하는지 검증한다.
    @Test
    void balanceOverflowCannotBeDeposited() {
        User user = new User("overflow@example.com", "hash", "Owner", UserStatus.ACTIVE);
        Account account = new Account(user, "66666666666666", new BigDecimal("999999999999999.9999"),
                AccountStatus.ACTIVE);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(70L, 7L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.deposit(7L, 70L, new BigDecimal("0.0001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThat(account.getBalance()).isEqualByComparingTo("999999999999999.9999");
        verify(ledgerEntries, never()).saveAndFlush(any());
    }

    // 정상 출금 시 잔액을 줄이고 음수 DEBIT 원장을 저장하는지 검증한다.
    @Test
    void withdrawsAndCreatesDebitLedgerEntry() {
        User user = new User("withdraw@example.com", "hash", "Withdraw User", UserStatus.ACTIVE);
        Account account = new Account(user, "88888888888888", new BigDecimal("100.0000"), AccountStatus.ACTIVE);
        when(users.findById(8L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(80L, 8L)).thenReturn(Optional.of(account));
        when(ledgerEntries.saveAndFlush(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WithdrawalResponse response = service.withdraw(8L, 80L, new BigDecimal("30.0000"));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntries).saveAndFlush(captor.capture());
        assertThat(account.getBalance()).isEqualByComparingTo("70.0000");
        assertThat(captor.getValue().getType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("-30.0000");
        assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo("70.0000");
        assertThat(response.amount()).isEqualByComparingTo("30.0000");
        assertThat(response.balanceAfter()).isEqualByComparingTo("70.0000");
    }

    // 출금액이 잔액보다 크면 ACCOUNT_003으로 거부하고 계좌와 원장을 유지하는지 검증한다.
    @Test
    void insufficientBalanceCannotBeWithdrawn() {
        User user = new User("insufficient@example.com", "hash", "Withdraw User", UserStatus.ACTIVE);
        Account account = new Account(user, "99999999999999", new BigDecimal("50.0000"), AccountStatus.ACTIVE);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(90L, 9L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.withdraw(9L, 90L, new BigDecimal("50.0001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        assertThat(account.getBalance()).isEqualByComparingTo("50.0000");
        verify(ledgerEntries, never()).saveAndFlush(any());
    }

    // 본인 소유가 아닌 계좌 출금은 ACCOUNT_001로 거부하는지 검증한다.
    @Test
    void foreignOrMissingAccountCannotBeWithdrawn() {
        User user = new User("withdraw-owner@example.com", "hash", "Owner", UserStatus.ACTIVE);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(100L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(10L, 100L, new BigDecimal("1.0000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        verify(ledgerEntries, never()).saveAndFlush(any());
    }

    // ACTIVE가 아닌 계좌 출금은 ACCOUNT_002로 거부하는지 검증한다.
    @Test
    void unavailableAccountCannotBeWithdrawn() {
        User user = new User("closed-account@example.com", "hash", "Owner", UserStatus.ACTIVE);
        Account account = new Account(user, "10101010101010", new BigDecimal("10.0000"), AccountStatus.CLOSED);
        when(users.findById(11L)).thenReturn(Optional.of(user));
        when(accounts.findByAccountIdAndUser_UserId(110L, 11L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.withdraw(11L, 110L, new BigDecimal("1.0000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_AVAILABLE));
        assertThat(account.getBalance()).isEqualByComparingTo("10.0000");
        verify(ledgerEntries, never()).saveAndFlush(any());
    }
}
