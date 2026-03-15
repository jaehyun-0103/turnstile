package com.turnstile.backend.domain.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.domain.reservation.controller.ReservationController;
import com.turnstile.backend.domain.reservation.dto.MyReservationResponse;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예약 도메인의 컨트롤러 엔드포인트 동작과 응답 규격을 검증하는 테스트입니다.
 */
@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReservationService reservationService;
    @MockitoBean private AuthInterceptor authInterceptor;
    @MockitoBean private LoginUserArgumentResolver loginUserArgumentResolver;

    @BeforeEach
    void setUp() throws Exception {
        given(authInterceptor.preHandle(any(), any(), any())).willReturn(true);
        given(loginUserArgumentResolver.supportsParameter(any())).willReturn(true);
        given(loginUserArgumentResolver.resolveArgument(any(), any(), any(), any())).willReturn(1L);
    }

    @Test
    @DisplayName("좌석 예약 요청 시 그룹 ID와 결제 토큰을 포함한 규격화된 JSON을 반환해야 한다")
    void should_return_reservation_response_on_reserve_seats() throws Exception {
        // given
        ReservationRequest request = new ReservationRequest(1L, 100L, List.of(10L, 11L));
        ReservationResponse response = new ReservationResponse("group-uuid-123", "PENDING", LocalDateTime.now(), "mock-token");

        given(reservationService.reserveSeats(any(ReservationRequest.class))).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value("group-uuid-123"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.accessToken").value("mock-token"));
    }

    @Test
    @DisplayName("내 예약 확인 시 예약 상세 목록을 JSON 배열로 반환해야 한다")
    void should_return_my_reservation_list() throws Exception {
        // given
        MyReservationResponse response = new MyReservationResponse(1L, "group-1", "아이유 콘서트", LocalDateTime.now(), 15, ReservationStatus.PENDING, 150000L, LocalDateTime.now());
        given(reservationService.getMyReservations(anyLong())).willReturn(List.of(response));

        // when & then
        mockMvc.perform(get("/api/v1/reservations/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value("group-1"))
                .andExpect(jsonPath("$[0].concertTitle").value("아이유 콘서트"))
                .andExpect(jsonPath("$[0].seatNo").value(15));
    }
}