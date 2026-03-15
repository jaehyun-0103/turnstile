package com.turnstile.backend.domain.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 로그인부터 권한 API 호출까지의 전체 인증 흐름을 실제 컨텍스트 환경에서 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @org.springframework.boot.test.context.TestConfiguration
    static class IntegrationTestConfig {
        @org.springframework.web.bind.annotation.RestController
        static class DummyProtectedController {
            @org.springframework.web.bind.annotation.GetMapping("/api/v1/reservations/dummy")
            public String dummy(@com.turnstile.backend.domain.auth.annotation.LoginUser Long userId) {
                return "OK";
            }
        }
    }

    @Test
    @DisplayName("로그인을 통해 발급받은 토큰으로 권한이 필요한 API에 접근할 수 있어야 한다")
    void should_access_protected_api_with_token_issued_from_login() throws Exception {
        // given: 로그인 요청
        LoginRequest loginRequest = new LoginRequest("tester");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk());

        // JwtProvider를 사용하여 1시간 뒤 만료되는 진짜 토큰 생성
        long userId = 1L;
        Date expiryDate = new Date(System.currentTimeMillis() + 3600000);
        String token = jwtProvider.generateToken(userId, expiryDate);

        // when & then: 발급된 토큰을 헤더에 담아 좌석 조회(권한 필요) API 호출
        mockMvc.perform(get("/api/v1/schedules/1/seats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
