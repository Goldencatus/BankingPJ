package com.bankingpj.backend.transfer.controller;

import com.bankingpj.backend.common.response.ApiResponse;
import com.bankingpj.backend.transfer.dto.TransferRequest;
import com.bankingpj.backend.transfer.dto.TransferResponse;
import com.bankingpj.backend.transfer.service.IdempotentTransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final IdempotentTransferService transferService;

    // 인증된 계좌 이체를 처리할 서비스를 주입받는다.
    public TransferController(IdempotentTransferService transferService) {
        this.transferService = transferService;
    }

    // JWT 회원과 필수 멱등성 키로 계좌 이체를 실행하고 공통 성공 응답을 반환한다.
    @PostMapping
    public ApiResponse<TransferResponse> transfer(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader(name = "Idempotency-Key", required = false)
                                                  @NotBlank(message = "Idempotency-Key는 필수입니다")
                                                  @Size(max = 100, message = "Idempotency-Key는 100자 이하여야 합니다")
                                                  String idempotencyKey,
                                                  @Valid @RequestBody TransferRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ApiResponse.success(transferService.transfer(userId, idempotencyKey, request.fromAccountId(),
                request.toAccountNumber(), request.amount()));
    }
}
