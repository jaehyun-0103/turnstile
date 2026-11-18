package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.time.ZoneId;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.turnstile.backend.support.ReservationPaymentTestSupport;

/**
 * 콘서트 예매 오픈 시간 및 회차 진행 여부에 따른 대기열 진입, 좌석 점유, 결제 제한 정책을 검증하는 통합 테스트입니다.
 */
class ConcertBookingTimeIntegrationTest extends ReservationPaymentTestSupport {

    @Test
    @DisplayName("티켓팅 오픈 시간 전에는 대기열 진입 요청을 차단하고 Q001 에러를 반환한다")
    void queueRejectsConcertBeforeOpening() throws Exception {
        // given: 티켓팅 오픈 시각을 현재보다 1시간 뒤로 설정
        setOpeningTime(currentTime().plusHours(1));

        // when & then: 대기열 진입 요청 시 400 응답 및 Q001 에러 코드, DB 미반영 확인
        enterQueueHttp()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("Q001"));

        verify(queue, never()).enterQueue(anyString(), anyString());
        assertNoWrites();
    }

    @Test
    @DisplayName("진행 예정인 미래 회차가 없는 콘서트는 대기열 진입 요청을 차단하고 S005 에러를 반환한다")
    void queueRejectsConcertWithoutFutureSchedule() throws Exception {
        // given: 모든 공연 회차 일시를 과거 시점으로 변경
        setConcertDates(currentTime().minusHours(1));

        // when & then: 대기열 진입 요청 시 400 응답 및 S005 에러 코드, DB 미반영 확인
        enterQueueHttp()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S005"));

        verify(queue, never()).enterQueue(anyString(), anyString());
        assertNoWrites();
    }

    @Test
    @DisplayName("과거 회차와 미래 회차가 공존하는 콘서트는 대기열 진입을 허용한다")
    void queueAllowsConcertWithPastAndFutureSchedules() throws Exception {
        // given: 기존 회차를 과거 시점으로 변경하고 미래 회차를 추가 생성함
        setConcertDates(currentTime().minusHours(1));

        schedules.save(ConcertSchedule.builder()
                .concert(concert)
                .concertDate(currentTime().plusDays(1))
                .totalSeats(1)
                .build());

        given(queue.enterQueue(anyString(), anyString())).willReturn(-1L);

        // when & then: 대기열 진입 요청 시 200 응답 및 ACTIVE 상태 반환 확인
        enterQueueHttp()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(queue).enterQueue(
                String.valueOf(concert.getId()),
                String.valueOf(userA.getId())
        );
        assertNoWrites();
    }

    @Test
    @DisplayName("티켓팅 오픈 시간 전에는 좌석 임시 점유 요청을 차단하고 Q001 에러를 반환한다")
    void reservationRejectsConcertBeforeOpening() throws Exception {
        // given: 티켓팅 오픈 시각을 현재보다 1시간 뒤로 설정
        setOpeningTime(currentTime().plusHours(1));

        // when & then: 좌석 점유 요청 시 400 응답 및 Q001 에러 코드, DB 미반영 확인
        reserveSeatsHttp(List.of(seatIds.getFirst()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("Q001"));

        assertNoWrites();
    }

    @Test
    @DisplayName("대기열 토큰이 있더라도 이미 시작된 과거 회차의 좌석 점유 요청은 차단한다")
    void reservationRejectsPastScheduleEvenWithQueueToken() throws Exception {
        // given: 공연 회차 일시를 과거 시점으로 변경
        setConcertDates(currentTime().minusHours(1));

        // when & then: 유효한 QUEUE 토큰으로 좌석 점유 요청 시 400 응답 및 S005 에러 코드 확인
        reserveSeatsHttp(List.of(seatIds.getFirst()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S005"));

        assertNoWrites();
    }

    @Test
    @DisplayName("과거 회차와 미래 회차가 섞인 콘서트에서 과거 좌석은 거부하고 미래 좌석만 점유를 허용한다")
    void mixedConcertRejectsPastSeatAndAllowsFutureSeat() throws Exception {
        // given: 기존 회차를 과거 시점으로 변경하고 미래 회차 및 전용 좌석을 추가 생성함
        setConcertDates(currentTime().minusHours(1));

        ConcertSchedule futureSchedule = schedules.save(
                ConcertSchedule.builder()
                        .concert(concert)
                        .concertDate(currentTime().plusDays(1))
                        .totalSeats(1)
                        .build()
        );

        Seat futureSeat = seats.save(Seat.builder()
                .schedule(futureSchedule)
                .seatNo(1)
                .price(1000L)
                .status(SeatStatus.AVAILABLE)
                .build());

        // when & then: 과거 회차 좌석 요청은 S005 에러로 거부되고 DB 변경이 없어야 함
        reserveSeatsHttp(List.of(seatIds.getFirst()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S005"));

        assertNoWrites();

        // when & then: 미래 회차 좌석 요청은 정상 점유(HOLD) 및 예약 생성에 성공함
        reserveSeatsHttp(List.of(futureSeat.getId()))
                .andExpect(status().isOk());

        assertThat(reservations.count()).isEqualTo(1);
        assertThat(seats.findById(futureSeat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HOLD);
    }

    @Test
    @DisplayName("좌석 점유 후 결제 시점에 공연이 이미 시작된 경우 결제를 거부하고 상태를 유지한다")
    void paymentRejectsConcertThatStartedAfterReservation() throws Exception {
        // given: 미래 회차 좌석을 정상 점유한 뒤 결제 직전 공연 일시를 과거 시점으로 변경
        ReservationResponse reserved = reserve(userA, seatIds);
        setConcertDates(currentTime().minusHours(1));

        // when & then: 결제 요청 시 400 응답 및 S005 에러 코드, 잔액 및 점유 상태 유지 확인
        mockMvc.perform(post("/api/v1/payments")
                        .header(
                                "Authorization",
                                "Bearer " + reserved.accessToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                paymentRequest(reserved.groupId(), 4000L)
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S005"));

        assertThat(payments.count()).isZero();
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance())
                .isEqualTo(10000L);

        assertThat(reservations.findAll())
                .hasSize(4)
                .allMatch(reservation ->
                        reservation.getStatus() == ReservationStatus.PENDING);

        assertThat(seats.findAll())
                .allMatch(seat -> seat.getStatus() == SeatStatus.HOLD);
    }

    /**
     * 한국 표준시(Asia/Seoul) 기준의 현재 시각을 반환합니다.
     */
    private LocalDateTime currentTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    /**
     * 현재 테스트 공연의 모든 회차 일시를 지정된 시각으로 일괄 갱신합니다.
     */
    private void setConcertDates(LocalDateTime date) {
        jdbc.update(
                "UPDATE concert_schedule SET concert_date = ? WHERE concert_id = ?",
                java.sql.Timestamp.valueOf(date),
                concert.getId()
        );
    }

    /**
     * 현재 테스트 공연의 티켓팅 오픈 시각을 지정된 시각으로 갱신합니다.
     */
    private void setOpeningTime(LocalDateTime date) {
        jdbc.update(
                "UPDATE concert SET ticketing_start_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(date),
                concert.getId()
        );
    }

    /**
     * IDENTITY 토큰을 사용해 대기열 진입 API를 호출합니다.
     */
    private org.springframework.test.web.servlet.ResultActions
    enterQueueHttp() throws Exception {
        String token = jwtProvider.generateToken(
                userA.getId(),
                TokenType.IDENTITY
        );

        return mockMvc.perform(post("/api/v1/queue/entry")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("concertId", concert.getId())
                )));
    }

    /**
     * QUEUE 토큰을 사용해 지정된 좌석 목록의 임시 점유(예약) API를 호출합니다.
     */
    private org.springframework.test.web.servlet.ResultActions
    reserveSeatsHttp(List<Long> selected) throws Exception {
        String token = jwtProvider.generateToken(
                userA.getId(),
                TokenType.QUEUE
        );

        return mockMvc.perform(post("/api/v1/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new ReservationRequest(concert.getId(), selected)
                )));
    }

    /**
     * 예약·결제 데이터 생성 및 사용자 잔액 차감, 좌석 상태 변경이 전혀 발생하지 않았음을 검증합니다.
     */
    private void assertNoWrites() {
        assertThat(reservations.count()).isZero();
        assertThat(payments.count()).isZero();

        assertThat(users.findById(userA.getId()).orElseThrow().getBalance())
                .isEqualTo(10000L);

        assertThat(seats.findAll())
                .allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE);
    }
}