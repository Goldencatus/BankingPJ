package com.bankingpj.backend.auth;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.dto.LoginRequest;
import com.bankingpj.backend.auth.repository.RefreshTokenRepository;
import com.bankingpj.backend.auth.service.LoginService;
import com.bankingpj.backend.auth.token.AccessTokenIssuer;
import com.bankingpj.backend.auth.token.RefreshTokenGenerator;
import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;
    @Mock private AccessTokenIssuer accessTokens;
    @Mock private RefreshTokenGenerator generator;
    @Mock private RefreshTokenRepository refreshTokens;
    private LoginService service;
    private final LoginRequest request = new LoginRequest("test@example.com", "test-password");

    // Initialize login with a dummy hash and mocked token collaborators.
    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        service = new LoginService(users, encoder, accessTokens, generator, refreshTokens,
                new AuthProperties("unused", 900, 3600, true), Clock.systemUTC());
    }

    // Unknown emails still perform password verification and must not reach token issuance.
    @Test
    void unknownEmailUsesDummyHashWithoutIssuingTokens() {
        assertLoginFailure(ErrorCode.INVALID_LOGIN_CREDENTIALS);
        verify(encoder).matches(request.password(), "dummy-hash");
    }

    // Incorrect passwords stop before any token generator or repository interaction.
    @Test
    void wrongPasswordDoesNotIssueTokens() {
        when(users.findByEmail(request.email())).thenReturn(Optional.of(
                new User(request.email(), "stored-hash", "Test User", UserStatus.ACTIVE)));
        assertLoginFailure(ErrorCode.INVALID_LOGIN_CREDENTIALS);
        verify(encoder).matches(request.password(), "stored-hash");
    }

    // Even a matching password cannot issue tokens for suspended or withdrawn users.
    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveUserDoesNotIssueTokens(UserStatus status) {
        when(users.findByEmail(request.email())).thenReturn(Optional.of(
                new User(request.email(), "stored-hash", "Test User", status)));
        when(encoder.matches(request.password(), "stored-hash")).thenReturn(true);
        assertLoginFailure(ErrorCode.LOGIN_NOT_ALLOWED);
    }

    // Assert the expected business error and that all token collaborators remain unused.
    private void assertLoginFailure(ErrorCode code) {
        assertThatThrownBy(() -> service.login(request)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verifyNoInteractions(accessTokens, generator, refreshTokens);
    }
}
