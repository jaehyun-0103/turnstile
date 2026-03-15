package com.turnstile.backend.domain.payment.dto;

import java.time.LocalDateTime;

/**
 * 결제 완료 후 사용자에게 반환되는 상세 응답 데이터입니다.
 */
public record PaymentResponse(
        Long paymentId,
        String status,
        LocalDateTime paidAt,
        Long balance
) {}