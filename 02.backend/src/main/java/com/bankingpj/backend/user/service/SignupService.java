package com.bankingpj.backend.user.service;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.dto.SignupRequest;
import com.bankingpj.backend.user.dto.SignupResponse;
import com.bankingpj.backend.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // 회원 저장소와 비밀번호 인코더를 주입받는다.
    public SignupService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // 이메일 중복을 확인하고 해시된 비밀번호로 회원을 저장한다.
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.email(), passwordHash, request.name(), UserStatus.ACTIVE);
        try {
            // Flush here so concurrent signups hitting the unique key also become HTTP 409.
            User savedUser = userRepository.saveAndFlush(user);
            return new SignupResponse(savedUser.getUserId());
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmail(exception)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            throw exception;
        }
    }

    // 무결성 오류가 이메일 UNIQUE 제약 위반인지 판별한다.
    private boolean isDuplicateEmail(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint != null && (constraint.equals("uk_users_email")
                        || constraint.endsWith(".uk_users_email"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
