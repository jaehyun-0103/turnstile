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

@WebMvcTest(ConcertController.class)
class ConcertControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ConcertService concertService;

    @MockitoBean private AuthInterceptor authInterceptor;
    @MockitoBean private LoginUserArgumentResolver loginUserArgumentResolver;

    @BeforeEach
    void setUp() throws Exception {
        // Mock 인터셉터가 요청을 차단하지 않고 무조건 통과시키도록 설정
        given(authInterceptor.preHandle(any(), any(), any())).willReturn(true);
    }

    @Test
    @DisplayName("콘서트 목록 조회 API")
    void getConcertsApiTest() throws Exception {
        // given: Mock 응답 데이터 세팅
        List<ConcertResponse> responses = List.of(new ConcertResponse(1L, "테스트 콘서트", "2024년 최고의 콘서트..."));
        given(concertService.getConcerts()).willReturn(responses);

        // when & then
        mockMvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("테스트 콘서트"))
                .andExpect(jsonPath("$[0].description").exists());
    }

    @Test
    @DisplayName("회차 목록 조회 API")
    void getSchedulesApiTest() throws Exception {
        // given: Mock 응답 데이터 세팅
        Long concertId = 1L;
        List<ConcertScheduleResponse> responses = List.of(new ConcertScheduleResponse(1L, LocalDateTime.now(), 5000));
        given(concertService.getSchedules(concertId)).willReturn(responses);

        // when & then
        mockMvc.perform(get("/api/v1/concerts/{concertId}/schedules", concertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].totalSeats").value(5000));
    }

    @Test
    @DisplayName("좌석 목록 조회 API")
    void getSeatsApiTest() throws Exception {
        // given: Mock 응답 데이터 세팅
        Long scheduleId = 1L;
        SeatResponse seat = new SeatResponse(101L, 1, "AVAILABLE", 150000L);
        SeatListResponse response = new SeatListResponse(scheduleId, List.of(seat));

        given(concertService.getSeats(scheduleId)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/schedules/{scheduleId}/seats", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(1))
                .andExpect(jsonPath("$.seats[0].seatId").value(101))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }
}