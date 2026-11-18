package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.turnstile.backend.support.ReservationPaymentTestSupport;

/**
 * 결제 API 호출 시 결제 토큰의 예약 그룹 일치 여부, 클레임 규격 및 예약 소유자 권한 검증을 수행하는 통합 테스트입니다.
 */
class PaymentAuthorizationIntegrationTest extends ReservationPaymentTestSupport {

    @Test
    @DisplayName("결제 토큰의 예약 그룹, 필수 클레임 및 예약 소유자 일치 여부에 따라 결제 요청을 인가하거나 차단한다")
    void paymentHttpChecksTokenGroupAndOwner() throws Exception {
        // given: userA가 2개 좌석을 임시 점유하여 결제 토큰을 발급받은 상태
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        // when & then: 토큰의 예약 그룹과 요청 본문의 그룹 ID가 다르면 403 응답 확인
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest("different-group", 2000L))))
                .andExpect(status().isForbidden());

        // when & then: 그룹 클레임이 누락된 기존 PAYMENT 토큰으로 요청 시 401 응답 확인
        String legacyToken = jwtProvider.generateToken(userA.getId(), TokenType.PAYMENT);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + legacyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isUnauthorized());

        // when & then: 서명과 그룹이 유효하더라도 예약 소유자가 다른 토큰으로 요청 시 403 응답 확인
        String otherUserToken = jwtProvider.generatePaymentToken(
                userB.getId(),
                reserved.groupId(),
                new java.util.Date(System.currentTimeMillis() + 60000)
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isForbidden());

        assertThat(payments.count()).isZero();
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(10000L);

        // when & then: 예약 소유자와 그룹 정보가 모두 일치하는 정상 토큰으로 요청 시 200 응답 및 결제 완료 확인
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + reserved.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 2000L))))
                .andExpect(status().isOk());

        assertThat(payments.count()).isEqualTo(1);
    }
}