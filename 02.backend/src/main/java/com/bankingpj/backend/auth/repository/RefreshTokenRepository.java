package com.bankingpj.backend.auth.repository;

import com.bankingpj.backend.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // Find a stored refresh-token record without querying with the plaintext token.
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
