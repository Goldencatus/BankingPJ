package com.bankingpj.backend.user.controller;

import com.bankingpj.backend.auth.dto.LoginRequest;
import com.bankingpj.backend.auth.dto.LoginResponse;
import com.bankingpj.backend.auth.dto.LoginResult;
import com.bankingpj.backend.auth.service.LoginService;
import com.bankingpj.backend.auth.service.RefreshTokenService;
import com.bankingpj.backend.auth.token.RefreshTokenCookieFactory;
import com.bankingpj.backend.common.response.ApiResponse;
import com.bankingpj.backend.user.dto.SignupRequest;
import com.bankingpj.backend.user.dto.SignupResponse;
import com.bankingpj.backend.user.service.SignupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory refreshTokenCookies;

    // 회원가입·로그인·토큰 Rotation 처리와 Refresh 쿠키 생성 의존성을 받는다.
    public AuthController(SignupService signupService, LoginService loginService,
                          RefreshTokenService refreshTokenService, RefreshTokenCookieFactory refreshTokenCookies) {
        this.signupService = signupService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    // 검증된 가입 요청을 처리하고 생성된 회원 정보를 HTTP 201로 반환한다.
    @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(signupService.signup(request)));
    }

    // 엑세스 토큰은 JSON으로, 리프레시 토큰은 HttpOnly 쿠키로만 반환한다.
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = loginService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.success(result.response()));
    }

    // HttpOnly 쿠키를 검증해 토큰을 Rotation하고 새 Refresh 쿠키를 반환한다.
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken) {
        LoginResult result = refreshTokenService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(result.refreshToken()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.success(result.response()));
    }

    // Refresh Token을 멱등적으로 폐기하고 브라우저 쿠키를 제거한다.
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken) {
        refreshTokenService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.delete().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.success());
    }
}
