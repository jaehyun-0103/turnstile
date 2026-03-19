package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.GlobalExceptionHandler;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 시스템 보안 계층인 인터셉터의 3-Tier 토큰 권한 검증과 리졸버의 동작을 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class AuthSecurityTest {

    private MockMvc mockMvc;

    @Mock private JwtProvider jwtProvider;

    @InjectMocks private AuthInterceptor authInterceptor;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SecurityTestController())
                .addInterceptors(authInterceptor)
                .setCustomArgumentResolvers(new LoginUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 3-Tier 권한 레벨별 인터셉터 동작을 검증하기 위한 더미 컨트롤러입니다.
     */
    @RestController
    static class SecurityTestController {
        @GetMapping("/api/v1/users/dummy")
        public ResponseEntity<String> level1Api(@LoginUser Long userId) {
            return ResponseEntity.ok("User ID: " + userId);
        }

        @GetMapping("/api/v1/payments/dummy")
        public ResponseEntity<String> level3Api(@LoginUser Long userId) {
            return ResponseEntity.ok("User ID: " + userId);
        }
    }

    @Test
    @DisplayName("유효한 IDENTITY 토큰으로 기본 권한 API 요청 시 인터셉터를 통과해야 한다")
    void should_pass_base_api_with_identity_token() throws Exception {
        // given: 유효한 IDENTITY 토큰 스펙 설정
        String token = "valid.identity.token";
        given(jwtProvider.extractUserId(token)).willReturn(1L);
        given(jwtProvider.extractTokenType(token)).willReturn(TokenType.IDENTITY);

        // when & then: Level 1 해당 API 호출 시 통과 확인
        mockMvc.perform(get("/api/v1/users/dummy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("User ID: 1"));
    }

    @Test
    @DisplayName("권한이 낮은 IDENTITY 토큰으로 결제(PAYMENT) API 요청 시 ACCESS_DENIED 예외가 발생해야 한다")
    void should_return_403_when_accessing_payment_with_identity_token() throws Exception {
        // given: 권한이 낮은 IDENTITY 토큰 스펙 설정
        String token = "valid.identity.token";
        given(jwtProvider.extractUserId(token)).willReturn(1L);
        given(jwtProvider.extractTokenType(token)).willReturn(TokenType.IDENTITY);

        // when & then: 권한 부족으로 인한 403 및 A003 코드 확인
        mockMvc.perform(get("/api/v1/payments/dummy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.getCode()));
    }

    @Test
    @DisplayName("토큰이 헤더에 아예 존재하지 않으면 인터셉터에서 401 응답을 반환해야 한다")
    void should_return_401_when_token_is_missing() throws Exception {
        // given: Authorization 헤더가 없는 상태의 요청

        // when & then: 토큰 누락으로 인한 INVALID_TOKEN 예외 확인
        mockMvc.perform(get("/api/v1/users/dummy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_TOKEN.getCode()));
    }

    @Test
    @DisplayName("토큰이 만료된 경우 인터셉터에서 401 응답을 반환해야 한다")
    void should_return_401_when_token_is_expired() throws Exception {
        // given: Provider 추출 로직에서 EXPIRED_TOKEN 예외를 던지도록 모킹
        String token = "expired.token";
        willThrow(new TurnstileException(ErrorCode.EXPIRED_TOKEN))
                .given(jwtProvider).extractUserId(token);

        // when & then: 글로벌 핸들러를 거쳐 정확한 에러 코드 확인
        mockMvc.perform(get("/api/v1/users/dummy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.EXPIRED_TOKEN.getCode()));
    }
}