package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
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

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    private MockMvc mockMvc;

    @Mock
    private JwtProvider jwtProvider;

    @InjectMocks
    private AuthInterceptor authInterceptor;

    @BeforeEach
    void setUp() {
        // 가짜 컨트롤러에 인터셉터와 리졸버를 직접 조립하여 세팅
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addInterceptors(authInterceptor)
                .setCustomArgumentResolvers(new LoginUserArgumentResolver())
                .build();
    }

    @RestController
    public static class TestController {
        @GetMapping("/api/v1/reservations/test")
        public ResponseEntity<String> testProtectedApi(@LoginUser Long userId) {
            return ResponseEntity.ok("User ID: " + userId);
        }
    }

    @Test
    @DisplayName("유효한 토큰을 헤더에 담아 보내면 인터셉터를 통과하고 userId가 주입")
    void validTokenPassesInterceptor() throws Exception {
        // given: 유효한 토큰 및 사용자 ID 세팅
        String validToken = "valid-jwt-token";
        given(jwtProvider.validateToken(validToken)).willReturn(true);
        given(jwtProvider.extractUserId(validToken)).willReturn(99L);

        // when & then
        mockMvc.perform(get("/api/v1/reservations/test")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(content().string("User ID: 99"));
    }

    @Test
    @DisplayName("토큰이 없거나 유효하지 않으면 401 에러를 반환하고 접근 차단")
    void invalidTokenBlockedByInterceptor() throws Exception {
        // given: 유효하지 않은 토큰 세팅
        String invalidToken = "invalid-jwt-token";
        given(jwtProvider.validateToken(invalidToken)).willReturn(false);

        // when & then 1: 유효하지 않은 토큰
        mockMvc.perform(get("/api/v1/reservations/test")
                        .header("Authorization", "Bearer " + invalidToken))
                .andExpect(status().isUnauthorized());

        // when & then 2: 공백의 토큰
        mockMvc.perform(get("/api/v1/reservations/test"))
                .andExpect(status().isUnauthorized());
    }
}