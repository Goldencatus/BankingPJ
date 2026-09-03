package com.bankingpj.backend;

import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(MySqlTestContainerConfiguration.class)
class BackendApplicationTests {

	// 테스트 컨테이너 설정으로 애플리케이션 컨텍스트가 시작되는지 확인한다.
	@Test
	void contextLoads() {
	}

}
