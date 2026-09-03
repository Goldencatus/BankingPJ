package com.bankingpj.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Signup does not need an automatically generated in-memory login account.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BackendApplication {

	// Spring Boot 백엔드 애플리케이션을 시작한다.
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
