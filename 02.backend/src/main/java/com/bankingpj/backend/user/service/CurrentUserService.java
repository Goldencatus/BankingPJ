package com.bankingpj.backend.user.service;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.dto.CurrentUserResponse;
import com.bankingpj.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    // 인증된 회원 ID로 DB의 최신 이름과 역할을 조회한다.
    @Transactional(readOnly = true)
    public CurrentUserResponse find(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
        return new CurrentUserResponse(user.getUserId(), user.getName(), user.getRole());
    }
}
