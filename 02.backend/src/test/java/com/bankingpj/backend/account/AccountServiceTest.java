package com.bankingpj.backend.account;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.account.service.AccountNumberGenerator;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
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
    private AccountService service;

    // 격리된 계좌 생성 서비스에 저장소와 번호 생성기 대역을 연결한다.
    @BeforeEach
    void setUp() {
        service = new AccountService(users, accounts, accountNumbers);
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
}
