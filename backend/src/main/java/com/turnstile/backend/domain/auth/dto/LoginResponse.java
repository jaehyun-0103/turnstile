package com.turnstile.backend.domain.auth.dto;

/**
 * 로그인 성공 시 반환되는 사용자 식별 정보 및 IDENTITY 접근 토큰입니다.
 */
public record LoginResponse(
        Long userId,
        String username,
        String name,
        Long balance,
        String accessToken
) {}