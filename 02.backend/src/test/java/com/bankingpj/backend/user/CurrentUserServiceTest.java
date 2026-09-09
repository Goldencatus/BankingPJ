package com.bankingpj.backend.user;

import com.bankingpj.backend.common.exception.BusinessException;
import com.bankingpj.backend.common.exception.ErrorCode;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserRole;
import com.bankingpj.backend.user.dto.CurrentUserResponse;
import com.bankingpj.backend.user.repository.UserRepository;
import com.bankingpj.backend.user.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock private UserRepository users;
    @InjectMocks private CurrentUserService currentUserService;

    // DB에 저장된 현재 회원의 이름과 역할을 안전한 응답으로 반환하는지 검증한다.
    @Test
    void returnsCurrentUserNameFromDatabase() {
        User user = mock(User.class);
        when(user.getUserId()).thenReturn(2L);
        when(user.getName()).thenReturn("홍길동");
        when(user.getRole()).thenReturn(UserRole.USER);
        when(users.findById(2L)).thenReturn(Optional.of(user));

        var response = currentUserService.find(2L);

        assertThat(response).isEqualTo(new CurrentUserResponse(2L, "홍길동", UserRole.USER));
    }

    // 토큰의 회원이 DB에 없으면 인증 오류로 처리하는지 검증한다.
    @Test
    void missingCurrentUserReturnsAuth003() {
        when(users.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.find(2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACCESS_TOKEN));
    }
}
