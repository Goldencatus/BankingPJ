package com.bankingpj.backend.auth.repository;

import com.bankingpj.backend.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 원문을 사용하지 않고 저장된 Refresh Token을 해시로 조회한다.
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 동일 토큰의 동시 Rotation을 막도록 회원과 토큰 행을 배타적으로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token join fetch token.user where token.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
