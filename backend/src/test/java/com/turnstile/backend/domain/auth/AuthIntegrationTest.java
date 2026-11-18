package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 로그인부터 3-Tier 권한 API 호출까지의 전체 인증 흐름을 실제 컨텍스트 환경에서 검증합니다.
 */
class AuthIntegrationTest extends DatabaseIntegrationTest {

    @Test
    @DisplayName("로그인으로 발급받은 IDENTITY 토큰으로 권한에 맞는 API는 통과하고, 상위 권한 API는 거부되어야 한다")
    void should_handle_tiered_authorization_flow_with_issued_token() throws Exception {
        // given: DB에 세팅해둔 실제 유저 정보로 로그인 요청 및 응답에서 토큰 추출
        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(userA.getUsername()))))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();

        // when & then 1: 발급받은 토큰(IDENTITY)으로 Level 1(유저) 잔액 조회 API 접근 -> 성공(200)
        mockMvc.perform(get("/api/v1/users/me/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userA.getId()))
                .andExpect(jsonPath("$.balance").value(10000));

        // when & then 2: 발급받은 토큰(IDENTITY)으로 Level 3(결제) API 접근 -> 권한 부족 실패(403)
        mockMvc.perform(post("/api/v1/payments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("A003"));
    }
}