package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.domain.user.controller.UserController;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.global.config.WebConfig;
import com.turnstile.backend.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유저 도메인의 컨트롤러 엔드포인트 동작과 응답 규격을 검증하는 테스트입니다.
 */
@WebMvcTest(
        controllers = UserController.class,
        properties = {
                "jwt.secret=test-key-for-controller-tests-only",
                "demo.balance-charge-enabled=true"
        }
)
@Import({
        WebConfig.class,
        JwtProvider.class,
        AuthInterceptor.class,
        LoginUserArgumentResolver.class,
        GlobalExceptionHandler.class
})
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserController userController;

    @MockitoBean private UserService userService;

    private String authorization(Long userId) {
        return "Bearer " + jwtProvider.generateToken(userId, TokenType.IDENTITY);
    }

    @Test
    @DisplayName("잔액 조회 성공 시 인증된 사용자의 200 상태 코드와 잔액 데이터를 반환해야 한다")
    void should_return_balance_using_authenticated_user() throws Exception {
        // given: 인증된 사용자의 잔액 응답 모킹
        given(userService.getBalance(1L)).willReturn(1000L);

        // when & then: API 호출 및 응답 검증
        mockMvc.perform(get("/api/v1/users/me/balance")
                        .header("Authorization", authorization(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.balance").value(1000));

        verify(userService).getBalance(1L);
    }

    @Test
    @DisplayName("잔액 충전 성공 시 각 사용자 본인의 200 상태 코드와 합산된 잔액 데이터를 반환해야 한다")
    void should_charge_own_balance_for_each_user() throws Exception {
        // given: 사용자별 충전 결과 모킹
        given(userService.chargeBalance(1L, 100L)).willReturn(1100L);
        given(userService.chargeBalance(2L, 100L)).willReturn(2100L);

        // when & then: 각 사용자 토큰으로 API 호출 및 응답 검증
        mockMvc.perform(patch("/api/v1/users/me/balance")
                        .header("Authorization", authorization(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.balance").value(1100));

        mockMvc.perform(patch("/api/v1/users/me/balance")
                        .header("Authorization", authorization(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(2))
                .andExpect(jsonPath("$.balance").value(2100));

        verify(userService).chargeBalance(1L, 100L);
        verify(userService).chargeBalance(2L, 100L);
    }

    @Test
    @DisplayName("요청 본문에 다른 userId가 포함되어도 인증된 본인 계좌로 충전되어야 한다")
    void should_ignore_body_user_id_and_charge_authenticated_user() throws Exception {
        // given: 본인 계좌 충전 결과 모킹
        given(userService.chargeBalance(1L, 100L)).willReturn(1100L);

        // when & then: 본문에 다른 userId를 포함하여 API 호출 및 본인 ID 응답 검증
        mockMvc.perform(patch("/api/v1/users/me/balance")
                        .header("Authorization", authorization(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"userId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1));

        verify(userService).chargeBalance(1L, 100L);
    }

    @Test
    @DisplayName("인증 토큰이 누락된 충전 요청 시 401 상태 코드를 반환해야 한다")
    void should_reject_when_token_is_missing() throws Exception {
        // when & then: Authorization 헤더 없이 API 호출 시 401 응답 및 서비스 미호출 검증
        mockMvc.perform(patch("/api/v1/users/me/balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("기존 userId 경로 변수 기반 충전 엔드포인트 호출 시 404 또는 405 상태 코드를 반환해야 한다")
    void should_reject_removed_user_id_path() throws Exception {
        // when & then: 제거된 경로로 API 호출 시 404/405 응답 및 서비스 미호출 검증
        mockMvc.perform(patch("/api/v1/users/2/balance")
                        .header("Authorization", authorization(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus())
                                .isIn(404, 405));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("충전 금액이 0 이하인 경우 400 상태 코드를 반환해야 한다")
    void should_reject_when_charge_amount_is_zero() throws Exception {
        // when & then: 유효하지 않은 금액(0)으로 API 호출 시 400 응답 및 서비스 미호출 검증
        mockMvc.perform(patch("/api/v1/users/me/balance")
                        .header("Authorization", authorization(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("시연용 잔액 충전 기능이 비활성화된 경우 403 상태 코드를 반환해야 한다")
    void should_reject_when_demo_charge_is_disabled() throws Exception {
        // given: 시연용 잔액 충전 비활성화 설정
        ReflectionTestUtils.setField(
                userController, "balanceChargeEnabled", false);

        try {
            // when & then: API 호출 시 403 응답 및 서비스 미호출 검증
            mockMvc.perform(patch("/api/v1/users/me/balance")
                            .header("Authorization", authorization(1L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":100}"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        } finally {
            ReflectionTestUtils.setField(
                    userController, "balanceChargeEnabled", true);
        }
    }
}