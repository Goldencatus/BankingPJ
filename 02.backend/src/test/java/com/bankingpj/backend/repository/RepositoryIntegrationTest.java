package com.bankingpj.backend.repository;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.account.repository.AccountRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MySqlTestContainerConfiguration.class)
class RepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesProductionFlywayMigrations() {
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE",
                String.class
        );

        assertThat(appliedVersions).contains("1", "2", "3");
    }

    @Test
    void savesUserAndFindsItByIdAndEmail() {
        User savedUser = userRepository.saveAndFlush(
                new User("user@example.com", "encoded-password", "Test User", UserStatus.ACTIVE)
        );
        Long userId = savedUser.getUserId();
        entityManager.clear();

        User foundById = userRepository.findById(userId).orElseThrow();
        User foundByEmail = userRepository.findByEmail("user@example.com").orElseThrow();

        assertThat(userId).isNotNull();
        assertThat(foundById.getEmail()).isEqualTo("user@example.com");
        assertThat(foundByEmail.getUserId()).isEqualTo(userId);
    }

    @Test
    void rejectsDuplicateUserEmail() {
        userRepository.saveAndFlush(
                new User("duplicate@example.com", "encoded-password-1", "First User", UserStatus.ACTIVE)
        );

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User("duplicate@example.com", "encoded-password-2", "Second User", UserStatus.ACTIVE)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesAccountWithUserAndFindsExactBalanceByAccountNumber() {
        User savedUser = userRepository.saveAndFlush(
                new User("owner@example.com", "encoded-password", "Account Owner", UserStatus.ACTIVE)
        );
        BigDecimal balance = new BigDecimal("123456789012345.6789");
        Account savedAccount = accountRepository.saveAndFlush(
                new Account(savedUser, "110-123-456789", balance, AccountStatus.ACTIVE)
        );
        Long accountId = savedAccount.getAccountId();
        entityManager.clear();

        Account foundAccount = accountRepository.findByAccountNumber("110-123-456789").orElseThrow();

        assertThat(accountId).isNotNull();
        assertThat(foundAccount.getAccountId()).isEqualTo(accountId);
        assertThat(foundAccount.getUser().getUserId()).isEqualTo(savedUser.getUserId());
        assertThat(foundAccount.getUser().getEmail()).isEqualTo("owner@example.com");
        assertThat(foundAccount.getBalance()).isEqualTo(balance);
    }

    @Test
    void rejectsDuplicateAccountNumber() {
        User savedUser = userRepository.saveAndFlush(
                new User("account-owner@example.com", "encoded-password", "Account Owner", UserStatus.ACTIVE)
        );
        accountRepository.saveAndFlush(
                new Account(savedUser, "110-000-000001", BigDecimal.ZERO, AccountStatus.ACTIVE)
        );

        assertThatThrownBy(() -> accountRepository.saveAndFlush(
                new Account(savedUser, "110-000-000001", BigDecimal.TEN, AccountStatus.ACTIVE)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
