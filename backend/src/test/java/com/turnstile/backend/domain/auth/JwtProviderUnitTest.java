package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
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

    @Test
    @DisplayName("누락되거나 부적절한 JWT 키는 생성 시 거부한다")
    void rejectMissingOrInvalidSecrets() {
        // when & then: null, 빈 값, 길이 미달 및 공백 포함 비밀키로 생성 시 예외 발생 확인
        assertThrows(IllegalStateException.class, () -> new JwtProvider(null));
        assertThrows(IllegalStateException.class, () -> new JwtProvider(""));
        assertThrows(IllegalStateException.class, () -> new JwtProvider(" ".repeat(40)));
        assertThrows(IllegalStateException.class, () -> new JwtProvider("a".repeat(31)));

        assertThrows(IllegalStateException.class,
                () -> new JwtProvider("a".repeat(32) + " "));
        assertThrows(IllegalStateException.class,
                () -> new JwtProvider("a".repeat(16) + "\t" + "b".repeat(16)));
        assertThrows(IllegalStateException.class,
                () -> new JwtProvider("a".repeat(16) + "\u00A0" + "b".repeat(16)));
    }

    @Test
    @DisplayName("다른 서명키로 발급한 토큰은 INVALID_TOKEN으로 거부한다")
    void rejectTokenSignedWithDifferentKey() {
        // given: 다른 비밀키를 사용하는 JwtProvider로 토큰 생성
        JwtProvider otherProvider = new JwtProvider("different-test-key-for-signature-check-2026");

        String token = otherProvider.generateToken(1L, TokenType.IDENTITY);

        // when & then: 기존 프로바이더로 검증 시 유효하지 않음 예외 발생 및 에러 코드 확인
        TurnstileException exception = assertThrows(
                TurnstileException.class,
                () -> jwtProvider.validateToken(token));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("서명 이후 payload가 변경된 토큰은 거부한다")
    void rejectTamperedPayload() {
        // given: 정상 발급된 토큰의 payload 부분을 임의로 변조함
        String token = jwtProvider.generateToken(1L, TokenType.IDENTITY);
        String[] parts = token.split("\\.");

        String changedPayload = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        "{\"sub\":\"999\",\"type\":\"PAYMENT\"}"
                                .getBytes(StandardCharsets.UTF_8));

        String tamperedToken =
                parts[0] + "." + changedPayload + "." + parts[2];

        // when & then: 변조된 토큰 검증 시 예외 발생 및 에러 코드 확인
        TurnstileException exception = assertThrows(
                TurnstileException.class,
                () -> jwtProvider.validateToken(tamperedToken));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("jwt.secret 설정이 없으면 Spring 컨텍스트 초기화에 실패한다")
    void failContextWhenSecretIsMissing() {
        // when & then: jwt.secret 프로퍼티 없이 컨텍스트 실행 시 초기화 실패 확인
        new ApplicationContextRunner()
                .withBean(
                        PropertySourcesPlaceholderConfigurer.class,
                        PropertySourcesPlaceholderConfigurer::new)
                .withUserConfiguration(JwtProvider.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("정상 JWT 키 설정이면 Spring 컨텍스트 초기화에 성공한다")
    void startContextWhenSecretIsConfigured() {
        // when & then: 유효한 jwt.secret 프로퍼티 설정 시 빈 등록 및 컨텍스트 초기화 성공 확인
        new ApplicationContextRunner()
                .withBean(
                        PropertySourcesPlaceholderConfigurer.class,
                        PropertySourcesPlaceholderConfigurer::new)
                .withUserConfiguration(JwtProvider.class)
                .withPropertyValues("jwt.secret=" + secretKey)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtProvider.class);
                });
    }
}