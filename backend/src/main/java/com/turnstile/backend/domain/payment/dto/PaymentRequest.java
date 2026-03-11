package com.turnstile.backend.domain.payment.dto;

public record PaymentRequest(
        Long userId,
        String groupId,
        Long amount
) {}