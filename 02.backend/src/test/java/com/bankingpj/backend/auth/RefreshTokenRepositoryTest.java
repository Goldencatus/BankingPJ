package com.bankingpj.backend.auth;

import com.bankingpj.backend.auth.domain.RefreshToken;
import com.bankingpj.backend.auth.repository.RefreshTokenRepository;
import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MySqlTestContainerConfiguration.class)
class RefreshTokenRepositoryTest {

    @Autowired private UserRepository users;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private EntityManager entityManager;

    // Persist and reload nullable revocation and DATETIME(6) timestamps through the production schema.
    @Test
    void roundTripsTokenHashUserAndTimestamps() {
        User user = createUser();
        LocalDateTime issuedAt = LocalDateTime.of(2026, 9, 3, 1, 2, 3, 123456000);
        RefreshToken saved = tokens.saveAndFlush(new RefreshToken(user, "a".repeat(64), issuedAt.plusDays(14), issuedAt));
        Long id = saved.getRefreshTokenId();
        entityManager.clear();

        RefreshToken found = tokens.findByTokenHash("a".repeat(64)).orElseThrow();
        assertThat(found.getRefreshTokenId()).isEqualTo(id);
        assertThat(found.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(found.getCreatedAt()).isEqualTo(issuedAt);
        assertThat(found.getExpiresAt()).isEqualTo(issuedAt.plusDays(14));
        assertThat(found.getRevokedAt()).isNull();
    }

    // Enforce the UNIQUE constraint on token hashes in MySQL.
    @Test
    void rejectsDuplicateTokenHash() {
        User user = createUser();
        LocalDateTime now = LocalDateTime.now();
        tokens.saveAndFlush(new RefreshToken(user, "b".repeat(64), now.plusDays(14), now));
        assertThatThrownBy(() -> tokens.saveAndFlush(new RefreshToken(user, "b".repeat(64), now.plusDays(14), now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // The user foreign key must reject a token referring to a nonexistent account.
    @Test
    void rejectsTokenForMissingUser() {
        User missing = entityManager.getReference(User.class, Long.MAX_VALUE);
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> tokens.saveAndFlush(new RefreshToken(missing, "c".repeat(64), now.plusDays(14), now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Deleting an owner must not silently cascade into token deletion.
    @Test
    void doesNotCascadeDeleteTokensWhenUserIsDeleted() {
        User user = createUser();
        LocalDateTime now = LocalDateTime.now();
        tokens.saveAndFlush(new RefreshToken(user, "d".repeat(64), now.plusDays(14), now));
        entityManager.clear();
        assertThatThrownBy(() -> users.deleteAllByIdInBatch(List.of(user.getUserId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Create an owner within the rollback-only repository test transaction.
    private User createUser() {
        return users.saveAndFlush(new User("refresh-owner@example.com", "test-hash", "Test User", UserStatus.ACTIVE));
    }
}
