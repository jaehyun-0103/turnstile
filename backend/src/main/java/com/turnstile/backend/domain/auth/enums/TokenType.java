package com.turnstile.backend.domain.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 하이브리드 토큰 인가 아키텍처에서 사용하는 권한 레벨 및 타입을 정의합니다.
 */
@Getter
@RequiredArgsConstructor
public enum TokenType {
    IDENTITY(1),
    QUEUE(2),
    PAYMENT(3);

    private final int level;

    /**
     * 현재 토큰의 레벨이 요청된 API의 요구 레벨을 충족하는지 검증합니다.
     * PAYMENT 토큰은 오직 PAYMENT 전용 API에서만 사용되도록 격리합니다.
     */
    public boolean hasAccess(TokenType required) {
        if (required == PAYMENT) {
            return this == PAYMENT;
        }
        return this.level >= required.level;
    }
}