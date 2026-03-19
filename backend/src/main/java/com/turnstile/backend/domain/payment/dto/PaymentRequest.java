package com.turnstile.backend.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 결제 승인을 요청하기 위한 데이터 전송 객체입니다.
 */
public record PaymentRequest(
        @NotBlank(message = "groupId는 필수 값입니다.")
        String groupId,

        @NotNull(message = "결제 금액은 필수 값입니다.")
        @Positive(message = "결제 금액은 0보다 커야 합니다.")
        Long amount
) {}