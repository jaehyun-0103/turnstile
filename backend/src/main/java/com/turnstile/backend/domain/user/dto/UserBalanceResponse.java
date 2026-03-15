package com.turnstile.backend.domain.user.dto;

/**
 * 사용자의 식별자와 현재 잔액 정보를 반환하는 응답 데이터입니다.
 */
public record UserBalanceResponse(
        Long userId,
        Long balance
) {}