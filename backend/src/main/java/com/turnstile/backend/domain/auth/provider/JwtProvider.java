package com.turnstile.backend.domain.auth.provider;

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

@Slf4j
@Component
public class JwtProvider {

    private final SecretKey secretKey;

    public JwtProvider(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 특정 만료 시점을 주입받는 동적 토큰 생성 메서드
    public String generateToken(Long userId, Date expiryDate) {
        Date now = new Date();

        String token = Jwts.builder()
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(expiryDate) // 주입받은 만료 시간 설정
                .signWith(secretKey)
                .compact();

        log.info("[Auth] Token Generate | Success (userId: {})", userId);
        return token;
    }

    // 토큰 유효성 검증
    public void validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            // 만료된 토큰 전용 에러 코드 적용
            log.warn("[Auth] Token Validate | Fail - Expired token");
            throw new TurnstileException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            // 변조되었거나 잘못된 형식의 토큰 에러 코드 적용
            log.warn("[Auth] Token Validate | Fail - Invalid token (message: {})", e.getMessage());
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }
    }

    // 토큰에서 userId 추출
    public Long extractUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return claims.get("userId", Long.class);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[Auth] Token Extract | Fail - Cannot extract userId");
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }
    }
}