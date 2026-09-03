package com.bankingpj.backend.account.repository;

import com.bankingpj.backend.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // 계좌번호로 계좌를 조회한다.
    Optional<Account> findByAccountNumber(String accountNumber);
}
