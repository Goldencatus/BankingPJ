package com.bankingpj.backend.common.security;

import com.bankingpj.backend.auth.config.TokenConfig;
import com.bankingpj.backend.auth.dto.LoginResponse;
import com.bankingpj.backend.auth.dto.LoginResult;
import com.bankingpj.backend.auth.service.LoginService;
import com.bankingpj.backend.auth.token.RefreshTokenCookieFactory;
import com.bankingpj.backend.user.controller.AuthController;
import com.bankingpj.backend.user.controller.UserController;
import com.bankingpj.backend.user.dto.SignupResponse;
import com.bankingpj.backend.user.service.SignupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {UserController.class, AuthController.class, JwtSecurityMvcTest.AuthorityController.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SecurityConfig.class, TokenConfig.class, RefreshTokenCookieFactory.class,
        JwtSecurityMvcTest.AuthorityController.class})
class JwtSecurityMvcTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtAuthenticationConverter converter;
    @MockitoBean private SignupService signupService;
    @MockitoBean private LoginService loginService;
    @MockitoSpyBean private RestAccessDeniedHandler deniedHandler;
    @MockitoSpyBean private RestAuthenticationEntryPoint entryPoint;

    // DB나 실제 환경 secret 없이 MVC 검증에 사용할 임시 서명 키와 설정을 제공한다.
    @DynamicPropertySource
    static void tokenProperties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("auth.access-secret", () -> secret);
        registry.add("auth.access-ttl-seconds", () -> 900);
        registry.add("auth.refresh-ttl-seconds", () -> 3600);
        registry.add("auth.cookie-secure", () -> false);
    }

    // 회원가입이 Bearer 토큰 없이 기존 Controller와 성공 응답까지 도달하는지 검증한다.
    @Test
    void signupRemainsPublic() throws Exception {
        when(signupService.signup(any())).thenReturn(new SignupResponse(42L));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"test@example.com","password":"test-password","name":"Test User"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"success":true,"data":{"userId":42},"error":null}
                        """, JsonCompareMode.STRICT));
        verify(signupService).signup(any());
    }

    // 로그인이 인증 토큰 없이 허용되고 기존 JSON·쿠키 응답을 유지하는지 검증한다.
    @Test
    void loginRemainsPublic() throws Exception {
        when(loginService.login(any())).thenReturn(new LoginResult(
                new LoginResponse("test-access-value", "Bearer", 900), "test-refresh-value"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"test@example.com","password":"test-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"accessToken":"test-access-value","tokenType":"Bearer","expiresIn":900},"error":null}
                        """, JsonCompareMode.STRICT))
                .andExpect(header().exists("Set-Cookie"));
        verify(loginService).login(any());
    }

    // 보호 API에 인증 토큰이 없으면 공통 AUTH_003 오류를 반환하는지 검증한다.
    @Test
    void missingTokenReturnsCommonUnauthorizedResponse() throws Exception {
        assertUnauthorized(mvc.perform(get("/api/users/me")));
    }

    // 실제 서명된 JWT로 회원 ID와 역할만 반환하고 세션을 만들지 않는지 검증한다.
    @Test
    void validTokenReturnsCurrentUserWithoutSessionOrSensitiveFields() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"userId":42,"role":"USER"},"error":null}
                        """, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    // 서명 일부가 변경된 JWT는 인증 실패로 처리하는지 검증한다.
    @Test
    void tamperedSignatureReturnsAuth003() throws Exception {
        String[] parts = validToken().split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tampered)));
    }

    // 만료된 JWT가 Decoder 검증 단계에서 거부되는지 검증한다.
    @Test
    void expiredTokenReturnsAuth003() throws Exception {
        String token = token("42", "USER", "bankingpj", Instant.now().minusSeconds(120));
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)));
    }

    // 서명이 맞아도 issuer가 다르면 인증되지 않는지 검증한다.
    @Test
    void wrongIssuerReturnsAuth003() throws Exception {
        String token = token("42", "USER", "another-issuer", Instant.now().plusSeconds(900));
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)));
    }

    // JWT 파싱 오류와 잘못된 Bearer 헤더도 내부 예외 없이 동일한 401로 처리하는지 검증한다.
    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer", "Bearer invalid token", "Bearer one, Bearer two"})
    void malformedBearerAuthenticationReturnsAuth003(String authorization) throws Exception {
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", authorization)));
    }

    // 필수 role이 없거나 허용되지 않은 형태이면 서명된 토큰도 거부하는지 검증한다.
    @ParameterizedTest
    @MethodSource("invalidRoles")
    void invalidRoleCannotAuthenticate(Object role) throws Exception {
        String token = token("42", role, "bankingpj", Instant.now().plusSeconds(900));
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)));
    }

    // 문자열 변형·다중 역할·숫자·객체를 통한 권한 주입 사례를 제공한다.
    static Stream<Object> invalidRoles() {
        return Stream.of(null, "", "ADMIN", "ROLE_USER", "USER ADMIN", "user",
                List.of("USER", "ADMIN"), 42, Map.of("role", "USER"));
    }

    // 회원 ID로 사용할 수 없는 sub는 Controller 진입 전에 401로 거부하는지 검증한다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "not-a-user-id", "9223372036854775808"})
    void invalidSubjectReturnsAuth003(String subject) throws Exception {
        String token = token(subject, "USER", "bankingpj", Instant.now().plusSeconds(900));
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)));
    }

    // 만료 시각 없는 JWT가 무기한 인증 수단으로 사용되지 않는지 검증한다.
    @Test
    void missingExpirationReturnsAuth003() throws Exception {
        String token = token("42", "USER", "bankingpj", null);
        assertUnauthorized(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)));
    }

    // 실제 SecurityContext에 ROLE_USER 권한이 들어가 메서드 인가를 통과하는지 검증한다.
    @Test
    void userRoleBecomesRoleUserAuthority() throws Exception {
        mvc.perform(get("/test/security/user").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"FACTOR_BEARER\",\"ROLE_USER\"]", JsonCompareMode.STRICT));
    }

    // 인증된 USER의 관리자 권한 부족을 전용 AccessDeniedHandler가 403으로 처리하는지 검증한다.
    @Test
    void insufficientAuthorityReturnsAuth004WithoutInternalDetails() throws Exception {
        mvc.perform(get("/test/security/admin").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"AUTH_004","message":"해당 작업에 대한 권한이 없음"}}
                        """, JsonCompareMode.STRICT));
        verify(deniedHandler).handle(any(), any(), isA(AccessDeniedException.class));
    }

    // MVC 내부 인증 예외도 500으로 바뀌거나 상세 메시지를 노출하지 않는지 검증한다.
    @Test
    void mvcAuthenticationFailureUsesEntryPoint() throws Exception {
        assertUnauthorized(mvc.perform(get("/test/security/authentication-failure")
                .header("Authorization", "Bearer " + validToken())));
        verify(entryPoint).commence(any(), any(), isA(BadCredentialsException.class));
    }

    // 인증 정보가 다음 요청이나 현재 스레드에 남지 않는지 검증한다.
    @Test
    void authenticationDoesNotLeakToNextRequest() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertUnauthorized(mvc.perform(get("/api/users/me")));
    }

    // URL 쿼리의 토큰을 Authorization 헤더 대신 인증 수단으로 사용하지 않는지 검증한다.
    @Test
    void queryParameterIsNotAnAuthenticationSource() throws Exception {
        assertUnauthorized(mvc.perform(get("/api/users/me").param("access_token", validToken())));
    }

    // 허용되지 않은 role에는 업무 권한 없이 프레임워크의 Bearer 인증 표식만 남는지 검증한다.
    @Test
    void converterDoesNotGrantUnknownAuthorities() {
        Jwt jwt = Jwt.withTokenValue("test-value").header("alg", "HS256").subject("42")
                .claim("role", "ADMIN").claim("scope", "admin").build();
        assertThat(converter.convert(jwt).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("FACTOR_BEARER");
    }

    // 정상 발급 정책에 맞는 짧은 수명의 테스트 JWT를 생성한다.
    private String validToken() {
        return token("42", "USER", "bankingpj", Instant.now().plusSeconds(900));
    }

    // claim을 제어하여 실제 서명과 Decoder 검증을 거치는 테스트 토큰을 생성한다.
    private String token(String subject, Object role, String issuer, Instant expiration) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(issuer).id(UUID.randomUUID().toString())
                .issuedAt(expiration == null ? Instant.now() : expiration.minusSeconds(900));
        if (subject != null) claims.subject(subject);
        if (role != null) claims.claim("role", role);
        if (expiration != null) claims.expiresAt(expiration);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
    }

    // 엄격한 JSON 비교로 401 응답에 토큰·비밀번호·스택·내부 예외 필드가 없는지 함께 검증한다.
    private void assertUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"AUTH_003","message":"인증이 필요하거나 Access Token이 유효하지 않음"}}
                        """, JsonCompareMode.STRICT));
    }

    @RestController
    @RequestMapping("/test/security")
    public static class AuthorityController {

        // USER 권한으로 접근 가능한 테스트 경로에서 실제 부여된 권한을 반환한다.
        @PreAuthorize("hasRole('USER')")
        @GetMapping("/user")
        public List<String> user(Authentication authentication) {
            return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        }

        // 운영 API를 추가하지 않고 관리자 권한 부족 상황을 발생시키는 테스트 경로다.
        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/admin")
        public String admin() {
            return "allowed";
        }

        // 내부 인증 예외가 공개 응답에 노출되지 않는지 확인할 테스트 경로다.
        @GetMapping("/authentication-failure")
        public String authenticationFailure() {
            throw new BadCredentialsException("internal password verification details");
        }
    }
}
