package com.bankingpj.backend.account.service;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.dto.AccountCreateResponse;
import com.bankingpj.backend.account.repository.AccountRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class AccountService {

    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 10;
    private static final BigDecimal INITIAL_BALANCE = BigDecimal.ZERO.setScale(4);

    private final UserRepository users;
    private final AccountRepository accounts;
    private final AccountNumberGenerator accountNumbers;
    private final LedgerEntryRepository ledgerEntries;

    // 계좌 소유자 조회, 번호 생성과 저장에 필요한 의존성을 주입받는다.
    public AccountService(UserRepository users, AccountRepository accounts, AccountNumberGenerator accountNumbers,
                          LedgerEntryRepository ledgerEntries) {
        this.users = users;
        this.accounts = accounts;
        this.accountNumbers = accountNumbers;
        this.ledgerEntries = ledgerEntries;
    }

    // 인증 회원을 확인하고 서버 정책의 초기값으로 새 계좌를 생성한다.
    @Transactional
    public AccountCreateResponse create(Long userId) {
        User user = activeUser(userId);

        String accountNumber = availableAccountNumber();
        Account account = accounts.saveAndFlush(
                new Account(user, accountNumber, INITIAL_BALANCE, AccountStatus.ACTIVE));
        return response(account);
    }

    // 인증된 ACTIVE 회원이 소유한 계좌를 식별자 순서의 응답 목록으로 반환한다.
    @Transactional(readOnly = true)
    public List<AccountCreateResponse> findAll(Long userId) {
        activeUser(userId);
        return accounts.findAllByUser_UserIdOrderByAccountIdAsc(userId).stream()
                .map(this::response)
                .toList();
    }

    // 인증된 ACTIVE 회원이 소유한 계좌 한 건을 상태와 관계없이 조회한다.
    @Transactional(readOnly = true)
    public AccountCreateResponse findOne(Long userId, Long accountId) {
        activeUser(userId);
        Account account = accounts.findByAccountIdAndUser_UserId(accountId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        return response(account);
    }

    // 본인 ACTIVE 계좌의 잔액을 늘리고 같은 트랜잭션에 CREDIT 원장을 저장한다.
    @Transactional
    public DepositResponse deposit(Long userId, Long accountId, BigDecimal amount) {
        Account account = activeOwnedAccount(userId, accountId);

        BigDecimal normalizedAmount = amount.setScale(4, RoundingMode.UNNECESSARY);
        BigDecimal expectedBalance = account.getBalance().add(normalizedAmount);
        if (integerDigits(expectedBalance) > 15) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        BigDecimal balanceAfter = account.deposit(normalizedAmount);
        LedgerEntry entry = ledgerEntries.saveAndFlush(
                new LedgerEntry(account, LedgerEntryType.CREDIT, normalizedAmount, balanceAfter));
        return new DepositResponse(account.getAccountId(), entry.getAmount(), entry.getBalanceAfter(),
                entry.getCreatedAt());
    }

    // 본인 ACTIVE 계좌의 잔액을 줄이고 같은 트랜잭션에 음수 DEBIT 원장을 저장한다.
    @Transactional
    public WithdrawalResponse withdraw(Long userId, Long accountId, BigDecimal amount) {
        Account account = activeOwnedAccount(userId, accountId);
        BigDecimal normalizedAmount = amount.setScale(4, RoundingMode.UNNECESSARY);
        if (account.getBalance().compareTo(normalizedAmount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        BigDecimal balanceAfter = account.withdraw(normalizedAmount);
        LedgerEntry entry = ledgerEntries.saveAndFlush(
                new LedgerEntry(account, LedgerEntryType.DEBIT, normalizedAmount.negate(), balanceAfter));
        return new WithdrawalResponse(account.getAccountId(), normalizedAmount, entry.getBalanceAfter(),
                entry.getCreatedAt());
    }

    // 저장된 번호와 충돌하지 않는 후보를 제한된 횟수 안에서 선택한다.
    private String availableAccountNumber() {
        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = accountNumbers.generate();
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate account number");
    }

    // JWT 회원의 존재와 ACTIVE 상태를 확인하여 금융 API 접근 여부를 결정한다.
    private User activeUser(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOGIN_NOT_ALLOWED);
        }
        return user;
    }

    // ACTIVE 회원이 소유한 ACTIVE 계좌만 금융 변경 대상으로 반환한다.
    private Account activeOwnedAccount(Long userId, Long accountId) {
        activeUser(userId);
        Account account = accounts.findByAccountIdAndUser_UserId(accountId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_AVAILABLE);
        }
        return account;
    }

    // Account Entity에서 외부에 공개할 계좌 필드만 응답 DTO로 변환한다.
    private AccountCreateResponse response(Account account) {
        return new AccountCreateResponse(account.getAccountId(), account.getAccountNumber(), account.getBalance(),
                account.getStatus(), account.getCreatedAt());
    }

    // DECIMAL(19,4)에 저장할 값의 정수부 자릿수를 계산한다.
    private int integerDigits(BigDecimal value) {
        return Math.max(value.precision() - value.scale(), 0);
    }
}
