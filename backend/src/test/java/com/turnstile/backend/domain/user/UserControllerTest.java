package com.turnstile.backend.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.user.controller.UserController;
import com.turnstile.backend.domain.user.dto.UserChargeRequest;
import com.turnstile.backend.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UserService userService;
    @MockitoBean private JwtProvider jwtProvider;

    @Test
    @DisplayName("GET /api/v1/users/me/balance : 잔액 조회 성공 시 200 OK와 잔액을 반환")
    void getBalance_Success() throws Exception {
        Long userId = 1L;
        Long expectedBalance = 150000L;

        when(userService.getBalance(userId)).thenReturn(expectedBalance);

        mockMvc.perform(get("/api/v1/users/me/balance")
                        .requestAttr("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance").value(expectedBalance));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{userId}/balance : 잔액 충전 성공 시 200 OK와 합산된 잔액을 반환")
    void chargeBalance_Success() throws Exception {
        Long userId = 1L;
        Long chargeAmount = 50000L;
        Long updatedBalance = 200000L; // 기존 잔액 15만 + 충전 5만 가정

        UserChargeRequest request = new UserChargeRequest(chargeAmount);

        when(userService.chargeBalance(userId, chargeAmount)).thenReturn(updatedBalance);

        mockMvc.perform(patch("/api/v1/users/{userId}/balance", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance").value(updatedBalance));
    }
}