package com.turnstile.backend.domain.auth.service;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사용자의 인증 절차 및 로그인 비즈니스 로직을 담당하는 서비스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;

    /**
     * 사용자 자격 증명을 확인하고 로그인 응답 정보를 생성합니다.
     * @param request 로그인 요청 정보
     * @return 로그인 결과 정보
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new TurnstileException(ErrorCode.USER_NOT_FOUND, "요청 아이디: " + request.username()));

        log.info("[Auth] Login | Success (userId: {}, username: {})", user.getId(), user.getUsername());

        return new LoginResponse(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getBalance()
        );
    }
}