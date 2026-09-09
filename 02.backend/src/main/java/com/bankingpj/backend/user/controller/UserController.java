package com.bankingpj.backend.user.controller;

import com.bankingpj.backend.common.response.ApiResponse;
import com.bankingpj.backend.user.dto.CurrentUserResponse;
import com.bankingpj.backend.user.service.CurrentUserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final CurrentUserService currentUserService;

    public UserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    // 검증된 JWT의 회원 ID로 DB의 최신 공개 사용자 정보를 반환한다.
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(currentUserService.find(Long.parseLong(jwt.getSubject())));
    }
}
