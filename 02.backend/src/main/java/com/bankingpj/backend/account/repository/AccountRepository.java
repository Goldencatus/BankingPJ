package com.bankingpj.backend.account.repository;

import com.bankingpj.backend.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
