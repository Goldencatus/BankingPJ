package com.bankingpj.backend.account.repository;

import com.bankingpj.backend.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // 계좌번호로 계좌를 조회한다.
    Optional<Account> findByAccountNumber(String accountNumber);

    // 새 계좌번호를 저장하기 전에 동일 번호의 존재 여부를 확인한다.
    boolean existsByAccountNumber(String accountNumber);

    // 인증 회원이 소유한 모든 상태의 계좌를 식별자 오름차순으로 조회한다.
    List<Account> findAllByUser_UserIdOrderByAccountIdAsc(Long userId);

    // 계좌 식별자와 인증 회원 식별자를 함께 사용하여 본인 계좌만 조회한다.
    Optional<Account> findByAccountIdAndUser_UserId(Long accountId, Long userId);

    // 본인 계좌 한 행에 쓰기 잠금을 획득해 최신 잔액으로 금융 변경을 처리하게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account "
            + "where account.accountId = :accountId and account.user.userId = :userId")
    Optional<Account> findOwnedByIdForUpdate(@Param("accountId") Long accountId, @Param("userId") Long userId);

    // 지정한 계좌 한 행에 쓰기 잠금을 획득하여 트랜잭션 종료까지 다른 변경을 대기시킨다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.accountId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") Long accountId);

    // 수취 계좌번호로 잠금 순서 계산에 필요한 식별자만 조회한다.
    @Query("select account.accountId from Account account where account.accountNumber = :accountNumber")
    Optional<Long> findAccountIdByAccountNumber(@Param("accountNumber") String accountNumber);
}
