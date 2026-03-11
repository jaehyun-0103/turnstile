package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    private JwtProvider jwtProvider;
    private final String SECRET_KEY = "test-secret-key-must-be-at-least-32-bytes";
    private final long EXPIRATION_TIME = 330000L;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(SECRET_KEY, EXPIRATION_TIME);
    }

    @Test
    @DisplayName("토큰 생성 및 유저 ID 추출 성공")
    void generateAndExtractTokenTest() {
        // given: 유저 ID 세팅
        Long userId = 100L;

        // when
        String token = jwtProvider.generateToken(userId);
        Long extractedUserId = jwtProvider.extractUserId(token);

        // then
        assertThat(token).isNotBlank();
        assertThat(extractedUserId).isEqualTo(userId);
    }

    @Test
    @DisplayName("유효한 토큰은 검증 통과")
    void validateValidTokenTest() {
        // given: 유저 ID에 대한 토큰 세팅
        String token = jwtProvider.generateToken(100L);

        // when
        boolean isValid = jwtProvider.validateToken(token);

        // then
        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰은 검증 실패")
    void validateExpiredTokenTest() {
        // given: 즉시 만료되는 토큰 세팅
        JwtProvider expiredProvider = new JwtProvider(SECRET_KEY, 0L);
        String expiredToken = expiredProvider.generateToken(100L);

        // when
        boolean isValid = jwtProvider.validateToken(expiredToken);

        // then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("위조되거나 잘못된 토큰은 검증 실패")
    void validateInvalidTokenTest() {
        // given: 서명이 유효하지 않은 임의의 토큰 세팅
        String invalidToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.invalid_payload.invalid_signature";

        // when
        boolean isValid = jwtProvider.validateToken(invalidToken);

        // then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("동적 TTL 적용: 주입된 특정 시점에 토큰이 정확히 만료")
    void generateTokenWithDynamicExpiryTest() throws InterruptedException {
        // given: 현재로부터 2초 뒤에 만료되도록 설정
        long duration = 2000L;
        java.util.Date expiryDate = new java.util.Date(System.currentTimeMillis() + duration);
        Long userId = 100L;

        // when: 동적 TTL 메서드로 토큰 생성
        String token = jwtProvider.generateToken(userId, expiryDate);

        // then: 생성 직후에는 유효함
        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.extractUserId(token)).isEqualTo(userId);

        // then: 2초 대기 후 만료 여부 확인 (동적 TTL이 제대로 박혔는지 검증)
        Thread.sleep(2100L);
        assertThat(jwtProvider.validateToken(token)).isFalse();
    }
}