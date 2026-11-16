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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;

/**
 * JWT 토큰의 생성, 서명 검증 및 파싱을 담당하는 유틸리티 클래스입니다.
 */
@Slf4j
@Component
public class JwtProvider {

    private static final int MIN_SECRET_BYTES = 32; // 최소 비밀키 길이(32바이트)

    // 기존 공개 기본키 및 큰따옴표가 포함된 기존 폴백 값의 SHA-256 지문 (사용 차단 식별용)
    private static final Set<String> REJECTED_SECRET_FINGERPRINTS = Set.of(
            "0c44a3ac2062998acec60215c55fa1a9bbdf885484900366e461e7a3dcfb3f8f",
            "92a57fed863e863e59389dd9868a7c5804453e842b9c697b0ba1ffd085319ce4"
    );

    private final SecretKey key;

    public JwtProvider(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must be configured.");
        }

        if (secret.codePoints().anyMatch(
                codePoint -> Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint))) {
            throw new IllegalStateException("JWT secret must not contain whitespace.");
        }

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT secret must contain at least 32 UTF-8 bytes.");
        }

        if (REJECTED_SECRET_FINGERPRINTS.contains(fingerprint(secretBytes))) {
            throw new IllegalStateException(
                    "The previously published JWT secret must not be reused.");
        }

        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 비밀키 바이트 배열의 SHA-256 해시 지문을 계산합니다.
    */
    private static String fingerprint(byte[] secretBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secretBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    /**
     * 기본 만료 시간을 사용하여 특정 타입의 JWT 토큰을 생성합니다.
     */
    public String generateToken(Long userId, TokenType type) {
        long now = (new Date()).getTime();
        long defaultValidityInMs = 3600000L; // 기본 만료 시간(1시간)
        Date validity = new Date(now + defaultValidityInMs);
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
            log.warn("[Auth] Parse Claims | Fail - Token is expired (code: {}, message: {})", ErrorCode.EXPIRED_TOKEN.getCode(), e.getMessage());
            throw new TurnstileException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[Auth] Parse Claims | Fail - Token is invalid (code: {}, message: {})", ErrorCode.INVALID_TOKEN.getCode(), e.getMessage());
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