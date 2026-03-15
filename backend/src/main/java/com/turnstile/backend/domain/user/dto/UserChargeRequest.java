package com.turnstile.backend.domain.user.dto;

/**
 * 사용자 잔액 충전을 위한 요청 데이터입니다.
 */
public record UserChargeRequest(
        Long amount
) {}