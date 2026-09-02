package com.bankingpj.backend;

import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(MySqlTestContainerConfiguration.class)
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
