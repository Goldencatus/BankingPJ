package com.bankingpj.backend.transfer.controller;

import com.bankingpj.backend.common.response.ApiResponse;
import com.bankingpj.backend.transfer.dto.TransferRequest;
import com.bankingpj.backend.transfer.dto.TransferResponse;
import com.bankingpj.backend.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    // 인증된 계좌 이체를 처리할 서비스를 주입받는다.
    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    // JWT 회원과 검증된 요청으로 계좌 이체를 실행하고 공통 성공 응답을 반환한다.
    @PostMapping
    public ApiResponse<TransferResponse> transfer(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody TransferRequest request) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ApiResponse.success(transferService.transfer(userId, request.fromAccountId(),
                request.toAccountNumber(), request.amount()));
    }
}
