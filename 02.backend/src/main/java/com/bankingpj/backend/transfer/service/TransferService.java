package com.bankingpj.backend.transfer.service;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.ledger.repository.LedgerEntryRepository;
import com.bankingpj.backend.transfer.domain.Transfer;
import com.bankingpj.backend.transfer.dto.TransferResponse;
import com.bankingpj.backend.transfer.repository.TransferRepository;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class TransferService {

    private final UserRepository users;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;

    // 이체의 회원·계좌·Transfer·Ledger 처리를 담당할 저장소를 주입받는다.
    public TransferService(UserRepository users, AccountRepository accounts, TransferRepository transfers,
                           LedgerEntryRepository ledgerEntries) {
        this.users = users;
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledgerEntries = ledgerEntries;
    }

    // 양쪽 계좌의 잔액 이동과 Transfer·Ledger 기록을 하나의 트랜잭션으로 완료한다.
    @Transactional
    public TransferResponse transfer(Long userId, Long fromAccountId, String toAccountNumber, BigDecimal amount) {
        activeUser(userId);
        Long toAccountId = accounts.findAccountIdByAccountNumber(toAccountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        LockedAccounts lockedAccounts = lockAccountsInIdOrder(fromAccountId, toAccountId);
        Account fromAccount = lockedAccounts.fromAccount();
        Account toAccount = lockedAccounts.toAccount();
        validateAccounts(userId, fromAccount, toAccount);

        BigDecimal normalizedAmount = amount.setScale(4, RoundingMode.UNNECESSARY);
        if (fromAccount.getBalance().compareTo(normalizedAmount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        BigDecimal expectedToBalance = toAccount.getBalance().add(normalizedAmount);
        if (integerDigits(expectedToBalance) > 15) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Transfer transfer = new Transfer(fromAccount, toAccount, normalizedAmount);
        transfer.startProcessing();
        transfers.saveAndFlush(transfer);

        BigDecimal fromBalanceAfter = fromAccount.withdraw(normalizedAmount);
        BigDecimal toBalanceAfter = toAccount.deposit(normalizedAmount);
        ledgerEntries.saveAllAndFlush(List.of(
                new LedgerEntry(fromAccount, LedgerEntryType.DEBIT, normalizedAmount.negate(),
                        fromBalanceAfter, transfer),
                new LedgerEntry(toAccount, LedgerEntryType.CREDIT, normalizedAmount,
                        toBalanceAfter, transfer)));

        transfer.complete();
        transfers.saveAndFlush(transfer);
        return new TransferResponse(transfer.getTransferId(), fromAccount.getAccountId(),
                toAccount.getAccountNumber(), normalizedAmount, transfer.getStatus(), fromBalanceAfter,
                transfer.getCompletedAt());
    }

    // 로그인 회원이 존재하고 ACTIVE 상태인지 확인한다.
    private User activeUser(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOGIN_NOT_ALLOWED);
        }
        return user;
    }

    // 두 계좌를 식별자 오름차순으로 잠가 반대 방향 이체도 같은 DB Lock 순서를 사용하게 한다.
    private LockedAccounts lockAccountsInIdOrder(Long fromAccountId, Long toAccountId) {
        Long firstId = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);
        Account firstAccount = lockedAccount(firstId);
        Account secondAccount = firstId.equals(secondId) ? firstAccount : lockedAccount(secondId);
        if (fromAccountId.equals(firstId)) {
            return new LockedAccounts(firstAccount, secondAccount);
        }
        return new LockedAccounts(secondAccount, firstAccount);
    }

    // 계좌 식별자로 PESSIMISTIC_WRITE 잠금을 획득하고 없는 계좌를 공통 오류로 변환한다.
    private Account lockedAccount(Long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    // 잠긴 최신 계좌로 출금 소유권·동일 계좌·양쪽 상태를 잔액 변경 전에 검증한다.
    private void validateAccounts(Long userId, Account fromAccount, Account toAccount) {
        if (!fromAccount.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        if (fromAccount.getAccountId().equals(toAccount.getAccountId())) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }
        if (fromAccount.getStatus() != AccountStatus.ACTIVE || toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_AVAILABLE);
        }
    }

    // DECIMAL(19,4)에 저장할 값의 정수부 자릿수를 계산한다.
    private int integerDigits(BigDecimal value) {
        return Math.max(value.precision() - value.scale(), 0);
    }

    private record LockedAccounts(Account fromAccount, Account toAccount) {
    }
}
