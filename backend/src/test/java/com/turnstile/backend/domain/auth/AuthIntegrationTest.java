package com.turnstile.backend.domain.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
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

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인부터 권한 API 호출까지의 전체 인증 흐름을 실제 컨텍스트 환경에서 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        // 테스트용 실제 유저를 DB에 미리 적재
        testUser = userRepository.save(User.builder()
                .username("tester")
                .name("테스터")
                .balance(0L)
                .build());
    }

    @AfterEach
    void tearDown() {
        // 테스트 종료 후 DB 정리
        userRepository.deleteAllInBatch();
    }

    // 타 도메인 구현 상태에 영향을 받지 않기 위한 통합 테스트 전용 더미 컨트롤러
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
        // given: DB에 세팅해둔 실제 유저 정보로 로그인 요청
        LoginRequest loginRequest = new LoginRequest(testUser.getUsername());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk());

        // given: JwtProvider를 사용하여 1시간 뒤 만료되는 진짜 토큰 생성
        Date expiryDate = new Date(System.currentTimeMillis() + 3600000);
        String token = jwtProvider.generateToken(testUser.getId(), TokenType.IDENTITY, expiryDate);

        // when & then: 타 도메인(seats)이 아닌, 테스트 전용 더미 보호 API를 호출
        mockMvc.perform(get("/api/v1/reservations/dummy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}