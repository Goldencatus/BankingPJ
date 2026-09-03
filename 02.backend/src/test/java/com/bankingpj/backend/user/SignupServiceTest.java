package com.bankingpj.backend.user;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserRole;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.dto.SignupRequest;
import com.bankingpj.backend.user.repository.UserRepository;
import com.bankingpj.backend.user.service.SignupService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    private SignupService service;
    private final SignupRequest request = new SignupRequest("user@example.com", "test-password", "Test User");

    // 회원가입 서비스를 테스트용 저장소와 인코더로 초기화한다.
    @BeforeEach
    void setUp() {
        service = new SignupService(userRepository, passwordEncoder);
    }

    // 해시만 Entity에 전달하고 기본 상태·역할과 회원 ID 반환을 검증한다.
    @Test
    void passesOnlyEncodedPasswordToEntityAndReturnsUserId() {
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-hash");
        User saved = mock(User.class);
        when(saved.getUserId()).thenReturn(10L);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(saved);

        assertThat(service.signup(request).userId()).isEqualTo(10L);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(user.capture());
        assertThat(user.getValue().getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(user.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getValue().getRole()).isEqualTo(UserRole.USER);
    }

    // 중복 이메일이면 비밀번호 인코딩과 저장 전에 중단하는지 검증한다.
    @Test
    void duplicateEmailStopsBeforeEncodingOrSaving() {
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL));

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).saveAndFlush(any());
    }

    // 사전 확인 후 이메일 UNIQUE 충돌도 USER_001로 변환되는지 검증한다.
    @Test
    void uniqueEmailCollisionAfterPrecheckAlsoReturnsUser001() {
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-hash");
        ConstraintViolationException collision = new ConstraintViolationException(
                "Duplicate key", new SQLException("Duplicate key", "23000", 1062), "users.uk_users_email");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint", collision));

        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL));
    }

    // 다른 무결성 오류를 중복 이메일로 잘못 변환하지 않는지 검증한다.
    @Test
    void unrelatedIntegrityFailureIsNotMisreportedAsDuplicateEmail() {
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-hash");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Other constraint");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.signup(request)).isSameAs(failure);
    }
}
