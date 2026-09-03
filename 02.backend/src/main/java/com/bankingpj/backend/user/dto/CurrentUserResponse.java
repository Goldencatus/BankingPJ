package com.bankingpj.backend.user.dto;

import com.bankingpj.backend.user.domain.UserRole;

public record CurrentUserResponse(Long userId, UserRole role) {
}
