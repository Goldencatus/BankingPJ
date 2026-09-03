package com.bankingpj.backend.user.controller;

import com.bankingpj.backend.common.response.ApiResponse;
import com.bankingpj.backend.user.domain.UserRole;
import com.bankingpj.backend.user.dto.CurrentUserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    // 검증된 JWT에서 현재 회원 ID와 역할만 읽어 DB 조회 없이 반환한다.
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(new CurrentUserResponse(Long.parseLong(jwt.getSubject()),
                UserRole.valueOf(jwt.getClaimAsString("role"))));
    }
}
