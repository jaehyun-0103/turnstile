package com.turnstile.backend.domain.auth;

import org.springframework.stereotype.Component;

@Component
public class JwtProvider {
    public String generateToken(Long userId) {
        // TODO: 나중에 JWT 암호화 로직으로 교체 예정
        return "temp-jwt-token-for-user-" + userId;
    }
}