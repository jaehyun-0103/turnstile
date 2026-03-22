package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.auth.interceptor.AuthInterceptor;
import com.turnstile.backend.domain.auth.resolver.LoginUserArgumentResolver;
import com.turnstile.backend.domain.concert.controller.ConcertController;
import com.turnstile.backend.domain.concert.dto.ConcertResponse;
import com.turnstile.backend.domain.concert.dto.ConcertScheduleResponse;
import com.turnstile.backend.domain.concert.dto.SeatListResponse;
import com.turnstile.backend.domain.concert.dto.SeatResponse;
import com.turnstile.backend.domain.concert.service.ConcertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 콘서트 도메인의 컨트롤러 엔드포인트 동작과 응답 규격을 검증하는 테스트입니다.
 */
@WebMvcTest(ConcertController.class)
class ConcertControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ConcertService concertService;
    @MockitoBean private AuthInterceptor authInterceptor;
    @MockitoBean private LoginUserArgumentResolver loginUserArgumentResolver;

    @BeforeEach
    void setUp() throws Exception {
        given(authInterceptor.preHandle(any(), any(), any())).willReturn(true);
    }

    @Test
    @DisplayName("콘서트 목록 조회 시 올바른 JSON 구조와 데이터를 반환해야 한다")
    void should_return_concert_list_with_correct_json_structure() throws Exception {
        // given: 콘서트 목록 응답 모킹
        LocalDateTime ticketingTime = LocalDateTime.of(2026, 3, 22, 12, 0);
        List<ConcertResponse> responses = List.of(new ConcertResponse(1L, "테스트 콘서트", "상세 설명", ticketingTime));
        given(concertService.getConcerts()).willReturn(responses);

        // when & then: API 호출 및 데이터 검증
        mockMvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("테스트 콘서트"))
                .andExpect(jsonPath("$[0].ticketingStartAt").exists());
    }

    @Test
    @DisplayName("특정 콘서트의 회차 목록 조회 시 올바른 데이터를 반환해야 한다")
    void should_return_schedule_list_for_specific_concert() throws Exception {
        // given: 회차 목록 응답 모킹
        Long concertId = 1L;
        List<ConcertScheduleResponse> responses = List.of(new ConcertScheduleResponse(10L, LocalDateTime.now(), 5000));
        given(concertService.getSchedules(concertId)).willReturn(responses);

        // when & then: API 호출 및 데이터 검증
        mockMvc.perform(get("/api/v1/concerts/{concertId}/schedules", concertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].totalSeats").value(5000));
    }

    @Test
    @DisplayName("특정 회차의 좌석 목록 조회 시 올바른 데이터를 반환해야 한다")
    void should_return_seat_list_for_specific_schedule() throws Exception {
        // given: 좌석 목록 응답 모킹
        Long scheduleId = 10L;
        SeatResponse seat = new SeatResponse(101L, 1, "AVAILABLE", 150000L);
        SeatListResponse response = new SeatListResponse(scheduleId, List.of(seat));
        given(concertService.getSeats(scheduleId)).willReturn(response);

        // when & then: API 호출 및 데이터 검증
        mockMvc.perform(get("/api/v1/schedules/{scheduleId}/seats", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(10))
                .andExpect(jsonPath("$.seats[0].seatId").value(101))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }
}