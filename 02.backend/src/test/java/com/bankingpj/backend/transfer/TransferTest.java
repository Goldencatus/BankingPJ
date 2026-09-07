package com.bankingpj.backend.transfer;

import com.bankingpj.backend.account.domain.Account;
import com.bankingpj.backend.account.domain.AccountStatus;
import com.bankingpj.backend.transfer.domain.Transfer;
import com.bankingpj.backend.transfer.domain.TransferStatus;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    // REQUESTED 이체가 PROCESSING을 거쳐 COMPLETED와 완료 시각으로 전환되는지 검증한다.
    @Test
    void completesRequestedTransferThroughProcessing() {
        Transfer transfer = transfer(new BigDecimal("10.0000"));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REQUESTED);
        transfer.startProcessing();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PROCESSING);
        transfer.complete();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getCompletedAt()).isNotNull();
    }

    // 요청되지 않은 이체의 처리 시작과 처리 중이 아닌 이체의 완료를 거부하는지 검증한다.
    @Test
    void rejectsInvalidStatusTransitions() {
        Transfer transfer = transfer(new BigDecimal("10.0000"));

        assertThatThrownBy(transfer::complete).isInstanceOf(IllegalStateException.class);
        transfer.startProcessing();
        assertThatThrownBy(transfer::startProcessing).isInstanceOf(IllegalStateException.class);
    }

    // Transfer 도메인이 0원과 음수 이체 금액을 생성 단계에서 거부하는지 검증한다.
    @Test
    void rejectsNonPositiveTransferAmount() {
        Account fromAccount = account("transfer-unit-from@example.com", "95000000000001");
        Account toAccount = account("transfer-unit-to@example.com", "95000000000002");

        assertThatThrownBy(() -> new Transfer(fromAccount, toAccount, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(fromAccount, toAccount, new BigDecimal("-1.0000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 상태 전환 단위 테스트에 사용할 두 계좌와 이체를 생성한다.
    private Transfer transfer(BigDecimal amount) {
        return new Transfer(account("transfer-from@example.com", "95000000000003"),
                account("transfer-to@example.com", "95000000000004"), amount);
    }

    // Transfer 단위 테스트에 사용할 ACTIVE 계좌를 생성한다.
    private Account account(String email, String accountNumber) {
        User user = new User(email, "hash", "Transfer User", UserStatus.ACTIVE);
        return new Account(user, accountNumber, BigDecimal.ZERO.setScale(4), AccountStatus.ACTIVE);
    }
}
