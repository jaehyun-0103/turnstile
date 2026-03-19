package com.turnstile.backend.domain.auth.provider;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 토큰의 생성, 서명 검증 및 파싱을 담당하는 유틸리티 클래스입니다.
 */
@Slf4j
@Component
public class JwtProvider {

    private final SecretKey key;
    private final long defaultValidityInMs = 3600000L; // 기본 만료 시간(1시간)

    public JwtProvider(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 기본 만료 시간을 사용하여 특정 타입의 JWT 토큰을 생성합니다.
     */
    public String generateToken(Long userId, TokenType type) {
        long now = (new Date()).getTime();
        Date validity = new Date(now + this.defaultValidityInMs);
        return generateToken(userId, type, validity);
    }

    /**
     * 명시된 만료 시간을 사용하여 특정 타입의 JWT 토큰을 생성합니다.
     */
    public String generateToken(Long userId, TokenType type, Date expiryDate) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", type.name())
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 토큰의 서명을 검증하고 Payload(Claims)를 파싱하여 반환합니다.
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("[Auth] Parse Claims | Fail - ErrorCode: {} (message: {})", ErrorCode.EXPIRED_TOKEN.getCode(), e.getMessage());
            throw new TurnstileException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[Auth] Parse Claims | Fail - ErrorCode: {} (message: {})", ErrorCode.INVALID_TOKEN.getCode(), e.getMessage());
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * 토큰의 유효성을 검증합니다.
     */
    public void validateToken(String token) {
        parseClaims(token);
    }

    /**
     * 토큰에서 사용자 식별자를 추출합니다.
     */
    public Long extractUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /**
     * 토큰에서 권한 타입(TokenType)을 안전하게 추출하고 검증합니다.
     */
    public TokenType extractTokenType(String token) {
        String typeStr = parseClaims(token).get("type", String.class);

        if (typeStr == null) {
            log.warn("[Auth] Extract Token Type | Fail - Type claim is missing");
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }

        try {
            return TokenType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            log.warn("[Auth] Extract Token Type | Fail - Unknown Token Type: {}", typeStr);
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }
    }
}