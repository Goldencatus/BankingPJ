package com.bankingpj.backend.account.controller;

import com.bankingpj.backend.account.dto.AccountCreateResponse;
import com.bankingpj.backend.account.service.AccountService;
import com.bankingpj.backend.common.response.ApiResponse;
import com.bankingpj.backend.ledger.dto.DepositRequest;
import com.bankingpj.backend.ledger.dto.DepositResponse;
import com.bankingpj.backend.ledger.dto.WithdrawalRequest;
import com.bankingpj.backend.ledger.dto.WithdrawalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    // 인증 회원의 계좌 생성을 처리할 서비스를 주입받는다.
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // 검증된 JWT의 회원 ID로 계좌를 생성하고 HTTP 201로 반환한다.
    @PostMapping
    public ResponseEntity<ApiResponse<AccountCreateResponse>> create(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(accountService.create(userId)));
    }

    // 검증된 JWT 회원이 소유한 계좌 목록만 정해진 순서로 반환한다.
    @GetMapping
    public ApiResponse<List<AccountCreateResponse>> findAll(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ApiResponse.success(accountService.findAll(userId));
    }

    // 양의 계좌 ID와 JWT 회원 ID를 함께 사용하여 본인 계좌 상세만 반환한다.
    @GetMapping("/{accountId}")
    public ApiResponse<AccountCreateResponse> findOne(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "accountId는 양수여야 합니다") Long accountId) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ApiResponse.success(accountService.findOne(userId, accountId));
    }

    // 검증된 입금액과 JWT 회원 ID로 본인 계좌의 입금 처리를 요청한다.
    @PostMapping("/{accountId}/deposits")
    public ApiResponse<DepositResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "accountId는 양수여야 합니다") Long accountId,
            @Valid @RequestBody DepositRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ApiResponse.success(accountService.deposit(userId, accountId, request.amount()));
    }

    // 검증된 출금액과 JWT 회원 ID로 본인 계좌의 출금 처리를 요청한다.
    @PostMapping("/{accountId}/withdrawals")
    public ApiResponse<WithdrawalResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "accountId는 양수여야 합니다") Long accountId,
            @Valid @RequestBody WithdrawalRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ApiResponse.success(accountService.withdraw(userId, accountId, request.amount()));
    }
}
