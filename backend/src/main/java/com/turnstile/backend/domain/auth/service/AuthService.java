package com.turnstile.backend.domain.auth.service;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


/**
 * 사용자의 인증 절차 및 로그인 비즈니스 로직을 담당하는 서비스입니다.
 */
@Slf4j
@Service
public class AuthService {

    /**
     * 사용자 자격 증명을 확인하고 로그인 응답 정보를 생성합니다.
     * @param request 로그인 요청 정보
     * @return 로그인 결과 정보
     */
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