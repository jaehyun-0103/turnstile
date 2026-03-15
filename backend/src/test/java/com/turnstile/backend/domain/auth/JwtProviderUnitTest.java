package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 토큰의 생성, 추출 및 유효성 검증 로직을 검증하는 단위 테스트입니다.
 */
class JwtProviderUnitTest {

    private JwtProvider jwtProvider;
    private final String secretKey = "test-secret-key-must-be-at-least-32-bytes";

    @BeforeEach
    void setUp() {
        // 리팩토링된 생성자에 맞게 시크릿 키만 주입
        jwtProvider = new JwtProvider(secretKey);
    }

    @Test
    @DisplayName("사용자 식별자를 포함한 토큰 생성 및 추출에 성공한다")
    void generateAndExtractTokenSuccess() {
        // given: 사용자 식별자와 만료 시점 설정
        Long userId = 100L;
        Date expiryDate = new Date(System.currentTimeMillis() + 3600000);

        // when: 토큰을 생성하고 식별자를 추출함
        String token = jwtProvider.generateToken(userId, expiryDate);
        Long extractedUserId = jwtProvider.extractUserId(token);

        // then: 생성된 토큰이 존재해야 하며 추출된 ID가 일치해야 함
        assertThat(token).isNotBlank();
        assertThat(extractedUserId).isEqualTo(userId);
    }

    @Test
    @DisplayName("만료된 토큰 검증 시 EXPIRED_TOKEN 예외가 발생한다")
    void validateTokenFailExpired() {
        // given: 이미 만료된 시점으로 토큰을 생성함
        Date expiredDate = new Date(System.currentTimeMillis() - 1000);
        String token = jwtProvider.generateToken(1L, expiredDate);

        // when & then: 검증 시 만료 예외 발생을 확인함
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> jwtProvider.validateToken(token));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_TOKEN);
    }

    @Test
    @DisplayName("위조된 토큰 검증 시 INVALID_TOKEN 예외가 발생한다")
    void validateTokenFailInvalid() {
        // given: 잘못된 형식의 토큰 문자열이 주어짐
        String invalidToken = "invalid.token.string";

        // when & then: 검증 시 유효하지 않음 예외 발생을 확인함
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> jwtProvider.validateToken(invalidToken));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }
}