package com.turnstile.backend.domain.payment.dto;

import java.time.LocalDateTime;

public record PaymentResponse(
        Long paymentId,
        String status,
        LocalDateTime paidAt,
        Long balance
) {}