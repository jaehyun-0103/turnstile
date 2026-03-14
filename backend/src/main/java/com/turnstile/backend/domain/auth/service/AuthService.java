package com.turnstile.backend.domain.auth.service;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthService {

    public LoginResponse login(LoginRequest request) {
        // TODO: 실제 DB 연동 시 아래 하드코딩된 Mock 데이터를 지우고 조회 로직으로 교체 예정
        log.info("[Auth] Login | Success (username: {})", request.username());
        return new LoginResponse(
                1L,
                request.username(),
                "Mock User",
                0L
        );
    }
}