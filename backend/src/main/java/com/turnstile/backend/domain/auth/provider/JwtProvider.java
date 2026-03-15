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

/**
 * JWT 토큰의 생성, 유효성 검증 및 정보 추출을 담당하는 컴포넌트입니다.
 */
@Slf4j
@Component
public class JwtProvider {

    private final SecretKey secretKey;

    public JwtProvider(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 사용자의 고유 식별자와 지정된 만료 시점을 바탕으로 토큰을 생성합니다.
     * @param userId 사용자 고유 식별자
     * @param expiryDate 토큰 만료 시점
     * @return 생성된 JWT 토큰
     */
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

    /**
     * 토큰의 서명 및 유효 기간을 검증합니다.
     * @param token 검증할 JWT 토큰
     * @throws TurnstileException 토큰이 만료되었거나 유효하지 않은 경우 발생
     */
    public void validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            log.warn("[Auth] Token Validate | Fail - Expired token");
            throw new TurnstileException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[Auth] Token Validate | Fail - Invalid token (message: {})", e.getMessage());
            throw new TurnstileException(ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * 토큰의 페이로드에서 사용자 고유 식별자를 추출합니다.
     * @param token JWT 토큰
     * @return 사용자 고유 식별자
     */
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