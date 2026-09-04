package com.bankingpj.backend.account.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {

    private static final int ACCOUNT_NUMBER_LENGTH = 14;
    private final SecureRandom random = new SecureRandom();

    // 예측하기 어려운 14자리 숫자 계좌번호 후보를 생성한다.
    public String generate() {
        StringBuilder accountNumber = new StringBuilder(ACCOUNT_NUMBER_LENGTH);
        for (int index = 0; index < ACCOUNT_NUMBER_LENGTH; index++) {
            accountNumber.append(random.nextInt(10));
        }
        return accountNumber.toString();
    }
}
