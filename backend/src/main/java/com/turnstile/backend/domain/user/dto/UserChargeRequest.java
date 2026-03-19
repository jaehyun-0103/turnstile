package com.turnstile.backend.domain.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 사용자 잔액 충전을 위한 요청 데이터입니다.
 */
public record UserChargeRequest(
        @NotNull(message = "충전 금액은 필수 값입니다.")
        @Positive(message = "충전 금액은 0보다 커야 합니다.")
        Long amount
) {}