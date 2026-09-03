package com.bankingpj.backend.user.repository;

import com.bankingpj.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // 이메일로 회원을 조회한다.
    Optional<User> findByEmail(String email);

    // 가입하려는 이메일의 사용 여부를 확인한다.
    boolean existsByEmail(String email);
}
