package com.bankingpj.backend.ledger;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.ledger.domain.LedgerEntry;
import com.bankingpj.backend.ledger.domain.LedgerEntryType;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntryTest {

    // CREDIT 양수와 DEBIT 음수만 원장 생성 규칙을 통과하는지 검증한다.
    @Test
    void acceptsAmountSignMatchingLedgerType() {
        Account account = account();

        assertThatCode(() -> new LedgerEntry(account, LedgerEntryType.CREDIT,
                new BigDecimal("1.0000"), new BigDecimal("1.0000"))).doesNotThrowAnyException();
        assertThatCode(() -> new LedgerEntry(account, LedgerEntryType.DEBIT,
                new BigDecimal("-1.0000"), new BigDecimal("0.0000"))).doesNotThrowAnyException();
    }

    // CREDIT의 0·음수와 DEBIT의 0·양수를 잘못된 원장 금액으로 거부하는지 검증한다.
    @Test
    void rejectsAmountSignNotMatchingLedgerType() {
        Account account = account();

        assertThatThrownBy(() -> new LedgerEntry(account, LedgerEntryType.CREDIT,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerEntry(account, LedgerEntryType.CREDIT,
                new BigDecimal("-1.0000"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerEntry(account, LedgerEntryType.DEBIT,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerEntry(account, LedgerEntryType.DEBIT,
                new BigDecimal("1.0000"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    // 원장 단위 테스트에 사용할 ACTIVE 계좌를 생성한다.
    private Account account() {
        User user = new User("ledger@example.com", "hash", "Ledger User", UserStatus.ACTIVE);
        return new Account(user, "77777777777777", BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
    }
}
