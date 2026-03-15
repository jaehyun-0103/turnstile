package com.turnstile.backend.domain.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.controller.AuthController;
import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.auth.service.AuthService;
import com.turnstile.backend.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 API의 엔드포인트 동작과 응답 규격을 검증하는 테스트입니다.
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AuthService authService;
    @MockitoBean private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("로그인 요청 시 입력받은 정보와 생성된 식별자를 JSON으로 반환한다")
    void loginApiSuccess() throws Exception {
        // given: 로그인 요청과 예상 응답 데이터 설정
        LoginRequest request = new LoginRequest("tester");
        LoginResponse response = new LoginResponse(1L, "tester", "Mock User", 0L);
        given(authService.login(any(LoginRequest.class))).willReturn(response);

        // when & then: API 호출 시 성공 상태 코드와 필드 값을 확인함
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.username").value("tester"));
    }
}