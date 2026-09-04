package com.bankingpj.backend.account;

import com.bankingpj.backend.account.service.AccountNumberGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberGeneratorTest {

    // 생성된 계좌번호가 모두 숫자로 구성된 정확한 14자리인지 검증한다.
    @Test
    void generatesFourteenDigitAccountNumbers() {
        AccountNumberGenerator generator = new AccountNumberGenerator();
        Set<String> generated = new HashSet<>();
        for (int count = 0; count < 100; count++) {
            String accountNumber = generator.generate();
            assertThat(accountNumber).matches("[0-9]{14}");
            generated.add(accountNumber);
        }
        assertThat(generated).hasSize(100);
    }
}
