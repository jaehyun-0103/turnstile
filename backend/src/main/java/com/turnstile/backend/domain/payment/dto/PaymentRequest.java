package com.turnstile.backend.domain.payment.dto;

/**
 * 결제 승인을 요청하기 위한 데이터 전송 객체입니다.
 */
public record PaymentRequest(
        Long userId,
        String groupId,
        Long amount
) {}