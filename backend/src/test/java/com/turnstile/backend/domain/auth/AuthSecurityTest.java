package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
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
 * 시스템 보안 계층인 인터셉터와 리졸버의 협력 동작을 검증합니다.
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
     * 보안 기능 검증을 위해 인터셉터가 적용된 API 경로를 제공하는 테스트용 컨트롤러입니다.
     */
    @RestController
    static class SecurityTestController {
        @GetMapping("/api/test/protected")
        public ResponseEntity<String> protectedApi(@LoginUser Long userId) {
            return ResponseEntity.ok("User ID: " + userId);
        }
    }

    @Test
    @DisplayName("유효한 토큰으로 요청 시 인터셉터를 통과하여 사용자 식별자가 주입되어야 한다")
    void should_inject_user_id_when_token_is_valid() throws Exception {
        // given: 유효한 토큰 스펙 설정
        String token = "valid.token";
        willDoNothing().given(jwtProvider).validateToken(token);
        given(jwtProvider.extractUserId(token)).willReturn(1L);

        // when & then: 인증이 필요한 API 호출 결과 확인
        mockMvc.perform(get("/api/test/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("User ID: 1"));
    }

    @Test
    @DisplayName("토큰이 헤더에 아예 존재하지 않으면 인터셉터에서 예외가 발생하고 401 응답을 반환해야 한다")
    void should_return_401_when_token_is_missing() throws Exception {
        // given: Authorization 헤더가 없는 상태의 요청

        // when & then: 토큰 누락으로 인한 INVALID_TOKEN 예외 및 401 확인
        mockMvc.perform(get("/api/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"));
    }

    @Test
    @DisplayName("토큰이 만료된 경우 인터셉터에서 예외가 발생하고 401 응답을 반환해야 한다")
    void should_return_401_when_token_is_expired() throws Exception {
        // given: Provider에서 EXPIRED_TOKEN 예외를 던지도록 모킹
        String token = "expired.token";
        willThrow(new TurnstileException(ErrorCode.EXPIRED_TOKEN))
                .given(jwtProvider).validateToken(token);

        // when & then: 글로벌 핸들러를 거쳐 정확한 에러 코드와 401 확인
        mockMvc.perform(get("/api/test/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A002"));
    }
}