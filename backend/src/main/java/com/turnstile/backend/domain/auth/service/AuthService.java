package com.turnstile.backend.domain.auth.service;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public LoginResponse login(LoginRequest request) {
        // TODO: 실제 DB 연동 시 아래 하드코딩된 Mock 데이터를 지우고 조회 로직으로 교체 예정
        return new LoginResponse(
                1L,
                request.username(),
                "홍길동",
                500000L
        );
    }
}