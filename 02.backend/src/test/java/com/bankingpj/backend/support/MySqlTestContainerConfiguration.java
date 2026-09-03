package com.bankingpj.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.security.SecureRandom;
import java.util.Base64;

@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainerConfiguration {

    // Supply an ephemeral signing key so tests never depend on local or production secrets.
    @Bean
    DynamicPropertyRegistrar testAuthProperties() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        return registry -> {
            registry.add("auth.access-secret", () -> secret);
            registry.add("auth.access-ttl-seconds", () -> 900);
            registry.add("auth.refresh-ttl-seconds", () -> 1209600);
            registry.add("auth.cookie-secure", () -> false);
        };
    }

    // 개발 DB와 격리된 MySQL 테스트 컨테이너를 제공한다.
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4")
                .withDatabaseName("bankingpj_test")
                .withUsername("test")
                .withPassword("test");
    }
}
