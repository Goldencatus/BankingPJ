package com.bankingpj.backend.user;

import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(MySqlTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserRoleMigrationTest {

    @Autowired
    private MySQLContainer mysql;

    // 기존 회원 데이터는 유지하면서 마이그레이션이 USER 역할을 채우는지 검증한다.
    @Test
    void migrationBackfillsExistingUserWithoutChangingExistingColumns() {
        // This context owns an isolated container, using the existing test configuration.
        // No application datasource or developer database is used.
        mysql.start();
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target("3").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("""
                INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
                VALUES ('legacy@example.com', 'existing-hash', 'Legacy User', 'SUSPENDED', NOW(6), NOW(6))
                """);
        var before = jdbc.queryForMap("SELECT * FROM users WHERE email = 'legacy@example.com'");

        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .load().migrate();

        var after = jdbc.queryForMap("SELECT * FROM users WHERE email = 'legacy@example.com'");
        assertThat(after.remove("role")).isEqualTo("USER");
        assertThat(after).isEqualTo(before);
    }
}
