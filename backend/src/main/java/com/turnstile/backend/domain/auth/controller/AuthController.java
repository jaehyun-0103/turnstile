package com.turnstile.backend.domain.auth.controller;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import com.turnstile.backend.domain.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 인증 및 권한 관련 요청을 처리하는 컨트롤러입니다.
 */
@Slf4j
@Tag(name = "1. Auth", description = "사용자 인증 및 토큰 발급 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 사용자 로그인을 수행하고 인증 토큰을 포함한 정보를 반환합니다.
     * @param request 로그인 요청 정보
     * @return 로그인 성공 결과 응답
     */
    @Operation(summary = "사용자 로그인", description = "식별자를 입력하여 로그인하고 **LEVEL 1 (IDENTITY)** 토큰을 발급받습니다.")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("[Auth] Login Attempt | (username: {})", request.username());
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}