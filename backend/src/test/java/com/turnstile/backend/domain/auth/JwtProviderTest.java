package com.turnstile.backend.domain.auth;

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
}