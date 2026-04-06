package com.turnstile.backend.domain.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인부터 3-Tier 권한 API 호출까지의 전체 인증 흐름을 실제 컨텍스트 환경에서 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        // 테스트용 실제 유저를 DB에 미리 적재
        testUser = userRepository.save(User.builder()
                .username("tester_auth_unique")
                .name("테스터")
                .balance(0L)
                .build());
    }

    @AfterEach
    void tearDown() {
        // 테스트 종료 후 DB 정리
        userRepository.delete(testUser);
    }

    // 타 도메인 상태에 영향을 받지 않기 위한 3-Tier 검증 전용 더미 컨트롤러
    @org.springframework.boot.test.context.TestConfiguration
    static class IntegrationTestConfig {
        @org.springframework.web.bind.annotation.RestController
        static class DummyProtectedController {
            @org.springframework.web.bind.annotation.GetMapping("/api/v1/users/dummy")
            public String identityLevelApi() { return "OK"; }

            @org.springframework.web.bind.annotation.GetMapping("/api/v1/payments/dummy")
            public String paymentLevelApi() { return "OK"; }
        }
    }

    @Test
    @DisplayName("로그인으로 발급받은 IDENTITY 토큰으로 권한에 맞는 API는 통과하고, 상위 권한 API는 거부되어야 한다")
    void should_handle_tiered_authorization_flow_with_issued_token() throws Exception {
        // given: DB에 세팅해둔 실제 유저 정보로 로그인 요청 및 응답에서 토큰 추출
        LoginRequest loginRequest = new LoginRequest(testUser.getUsername());
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String issuedToken = jsonNode.get("accessToken").asText();

        // when & then 1: 발급받은 토큰(IDENTITY)으로 Level 1(유저) API 접근 -> 성공(200)
        mockMvc.perform(get("/api/v1/users/dummy")
                        .header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk());

        // when & then 2: 발급받은 토큰(IDENTITY)으로 Level 3(결제) API 접근 -> 권한 부족 실패(403)
        mockMvc.perform(get("/api/v1/payments/dummy")
                        .header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isForbidden());
    }
}