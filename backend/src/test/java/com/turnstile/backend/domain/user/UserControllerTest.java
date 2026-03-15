package com.turnstile.backend.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.domain.user.controller.UserController;
import com.turnstile.backend.domain.user.dto.UserChargeRequest;
import com.turnstile.backend.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유저 도메인의 컨트롤러 엔드포인트 동작과 응답 규격을 검증하는 테스트입니다.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UserService userService;
    @MockitoBean private AuthInterceptor authInterceptor;
    @MockitoBean private LoginUserArgumentResolver loginUserArgumentResolver;

    @BeforeEach
    void setUp() throws Exception {
        // 인터셉터 우회 및 커스텀 파라미터 리졸버 동작 모킹
        given(authInterceptor.preHandle(any(), any(), any())).willReturn(true);
        given(loginUserArgumentResolver.supportsParameter(any())).willReturn(true);
        given(loginUserArgumentResolver.resolveArgument(any(), any(), any(), any())).willReturn(1L);
    }

    @Test
    @DisplayName("잔액 조회 성공 시 200 상태 코드와 잔액 데이터를 반환해야 한다")
    void should_return_balance_and_200_ok() throws Exception {
        // given: 잔액 응답 모킹
        Long expectedBalance = 150000L;
        given(userService.getBalance(1L)).willReturn(expectedBalance);

        // when & then: API 호출 및 응답 검증
        mockMvc.perform(get("/api/v1/users/me/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.balance").value(expectedBalance));
    }

    @Test
    @DisplayName("잔액 충전 성공 시 200 상태 코드와 합산된 잔액 데이터를 반환해야 한다")
    void should_return_updated_balance_after_charge() throws Exception {
        // given: 충전 요청 및 결과 모킹
        Long chargeAmount = 50000L;
        Long updatedBalance = 200000L;
        UserChargeRequest request = new UserChargeRequest(chargeAmount);

        given(userService.chargeBalance(1L, chargeAmount)).willReturn(updatedBalance);

        // when & then: API 호출 및 응답 검증
        mockMvc.perform(patch("/api/v1/users/1/balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.balance").value(updatedBalance));
    }
}