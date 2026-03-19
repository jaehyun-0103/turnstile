package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
        jwtProvider = new JwtProvider(secretKey);
    }

    @Test
    @DisplayName("사용자 식별자와 타입을 포함한 토큰 생성 및 추출에 성공한다")
    void generateAndExtractTokenSuccess() {
        // given: 사용자 식별자와 만료 시점 설정
        Long userId = 100L;
        Date expiryDate = new Date(System.currentTimeMillis() + 3600000);

        // when: 토큰 생성 시 TokenType 명시
        String token = jwtProvider.generateToken(userId, TokenType.IDENTITY, expiryDate);
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
        String token = jwtProvider.generateToken(1L, TokenType.QUEUE, expiredDate);

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

    @Test
    @DisplayName("정상적인 토큰에서 권한 타입을 성공적으로 추출한다")
    void extractTokenTypeSuccess() {
        // given: PAYMENT 타입의 토큰을 생성함
        String token = jwtProvider.generateToken(1L, TokenType.PAYMENT);

        // when: 토큰에서 권한 타입을 추출함
        TokenType extractedType = jwtProvider.extractTokenType(token);

        // then: 추출된 타입이 PAYMENT와 일치해야 함
        assertThat(extractedType).isEqualTo(TokenType.PAYMENT);
    }

    @Test
    @DisplayName("토큰에 권한 타입이 누락된 경우 INVALID_TOKEN 예외가 발생한다")
    void extractTokenTypeFailMissingType() {
        // given: 권한 타입(type) 클레임이 누락된 임의의 토큰 생성
        String tokenWithoutType = Jwts.builder()
                .subject("1")
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)))
                .compact();

        // when & then: 타입 추출 시 예외 발생 및 에러 코드 확인
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> jwtProvider.extractTokenType(tokenWithoutType));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("알 수 없는 권한 타입이 조작되어 들어간 토큰 추출 시 INVALID_TOKEN 예외가 발생한다")
    void extractTokenTypeFailUnknownType() {
        // given: 존재하지 않는 권한 타입(HACKER_ADMIN)이 포함된 위조 토큰 생성
        String tokenWithUnknownType = Jwts.builder()
                .subject("1")
                .claim("type", "HACKER_ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)))
                .compact();

        // when & then: 타입 추출 시 예외 발생 및 에러 코드 확인
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> jwtProvider.extractTokenType(tokenWithUnknownType));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }
}