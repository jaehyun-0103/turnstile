package com.turnstile.backend.domain.reservation;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.reservation.dto.ReservationRequest;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.reservation.service.ReservationService;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 예약 서비스의 비즈니스 규칙(인가, 수량 제한, 선점 여부) 예외를 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @InjectMocks private ReservationService reservationService;

    @Mock private ReservationRepository reservationRepository;
    @Mock private QueueRedisRepository queueRedisRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        // @Value로 분리된 비즈니스 정책 주입
        ReflectionTestUtils.setField(reservationService, "maxSeatsPerUser", 4);
        ReflectionTestUtils.setField(reservationService, "holdTimeSeconds", 300L);
        ReflectionTestUtils.setField(reservationService, "paymentExpirySeconds", 330L);
    }

    @Test
    @DisplayName("이미 결제 대기(PENDING) 중인 예약이 있는 유저가 예약 시도 시 ALREADY_HAS_PENDING_RESERVATION 예외가 발생해야 한다")
    void should_throw_exception_when_user_already_has_pending_reservation() {
        // given: PENDING 예약을 가진 유저 모킹
        Long userId = 1L;
        ReservationRequest request = new ReservationRequest(100L, List.of(10L));

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, request.concertId(), ReservationStatus.PENDING)).willReturn(true);

        // when & then: 가장 첫 번째 검증에서 차단되는지 확인
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_HAS_PENDING_RESERVATION);
    }

    @Test
    @DisplayName("대기열을 통과하지 않은(인가되지 않은) 유저가 예약 시도 시 QUEUE_NOT_ACTIVE 예외가 발생해야 한다")
    void should_throw_exception_when_user_is_not_active_in_queue() {
        // given: 대기열에 없는 유저 모킹
        Long userId = 1L;
        Long concertId = 100L;
        ReservationRequest request = new ReservationRequest(concertId, List.of(10L, 11L));

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, request.concertId(), ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(false);

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.QUEUE_NOT_ACTIVE);
    }

    @Test
    @DisplayName("1인당 예매 가능 좌석 수(4개)를 초과하여 요청 시 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_exceeding_max_seat_limit() {
        // given: 인가된 유저가 5개의 좌석을 요청
        Long userId = 1L;
        Long concertId = 100L;
        ReservationRequest request = new ReservationRequest(concertId, List.of(1L, 2L, 3L, 4L, 5L));

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, request.concertId(), ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(true);

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("요청 좌석 리스트가 비어있을 경우 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_seat_ids_are_empty() {
        // given: 대기열은 통과했으나 빈 좌석 리스트를 포함한 요청 세팅
        Long userId = 1L;
        Long concertId = 100L;
        ReservationRequest request = new ReservationRequest(concertId, java.util.Collections.emptyList());

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, request.concertId(), ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(true);

        // when & then: 하한선 검증 실패로 인한 예외 및 메시지 확인
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThat(exception.getMessage()).contains("최소 1개");
    }

    @Test
    @DisplayName("요청 좌석 리스트가 null일 경우 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_seat_ids_are_null() {
        // given: 대기열은 통과했으나 좌석 리스트가 null인 요청 세팅
        Long userId = 1L;
        Long concertId = 100L;
        ReservationRequest request = new ReservationRequest(concertId, null);

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, request.concertId(), ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(true);

        // when & then: 하한선 검증 실패로 인한 예외 및 메시지 확인
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThat(exception.getMessage()).contains("최소 1개");
    }

    @Test
    @DisplayName("요청한 좌석 ID 목록 중 존재하지 않는 좌석이 포함되어 있으면 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_requesting_non_existent_seat() {
        // given: 2개의 좌석을 요청했으나 DB에서는 1개만 조회되는 상황 세팅
        Long userId = 1L;
        Long concertId = 100L;
        List<Long> requestedSeats = List.of(10L, 999L);
        ReservationRequest request = new ReservationRequest(concertId, requestedSeats);

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, concertId, ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(true);

        Seat mockSeat = mock(Seat.class);
        given(seatRepository.findAllByIdWithScheduleAndConcert(requestedSeats)).willReturn(List.of(mockSeat));

        // when & then: DB 락 획득 전 최전방 차단 검증
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("요청한 좌석들이 서로 다른 회차(Schedule)에 속해 있을 경우 예매가 거부되어야 한다")
    void should_throw_exception_when_seats_belong_to_different_schedules() {
        // given: 하나는 1회차, 하나는 2회차 좌석을 교차 예매하려는 시도
        Long userId = 1L;
        Long concertId = 100L;
        List<Long> requestedSeats = List.of(10L, 11L);
        ReservationRequest request = new ReservationRequest(concertId, requestedSeats);

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, concertId, ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(true);

        Seat mockSeat1 = mock(Seat.class);
        Seat mockSeat2 = mock(Seat.class);
        ConcertSchedule schedule1 = mock(ConcertSchedule.class);
        ConcertSchedule schedule2 = mock(ConcertSchedule.class);

        // NPE 방지를 위해 Concert Mock 객체 추가
        Concert mockConcert = mock(Concert.class);
        given(mockConcert.getId()).willReturn(concertId);
        given(schedule1.getConcert()).willReturn(mockConcert);
        given(schedule2.getConcert()).willReturn(mockConcert);

        given(mockSeat1.getSchedule()).willReturn(schedule1);
        given(mockSeat2.getSchedule()).willReturn(schedule2);
        given(schedule1.getId()).willReturn(1L);
        given(schedule2.getId()).willReturn(2L); // 서로 다른 회차로 세팅

        given(seatRepository.findAllByIdWithScheduleAndConcert(requestedSeats)).willReturn(List.of(mockSeat1, mockSeat2));

        // when & then: 크로스 스케줄 부킹 방어
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("요청한 콘서트 ID와 실제 좌석이 속한 콘서트 ID가 일치하지 않으면 예매가 거부되어야 한다")
    void should_throw_exception_when_concert_id_mismatches() {
        // given: 100번 콘서트의 대기열을 통과하고, 200번 콘서트 좌석을 요청하는 우회 공격 시도
        Long userId = 1L;
        Long requestedConcertId = 100L;
        Long actualConcertId = 200L;
        List<Long> requestedSeats = List.of(10L);
        ReservationRequest request = new ReservationRequest(requestedConcertId, requestedSeats);

        given(reservationRepository.existsByUserIdAndConcertIdAndStatus(userId, requestedConcertId, ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(requestedConcertId), String.valueOf(userId))).willReturn(true);

        Seat mockSeat = mock(Seat.class);
        ConcertSchedule mockSchedule = mock(ConcertSchedule.class);
        Concert mockConcert = mock(Concert.class);

        given(mockSeat.getSchedule()).willReturn(mockSchedule);
        given(mockSchedule.getConcert()).willReturn(mockConcert);
        given(mockConcert.getId()).willReturn(actualConcertId); // 200번

        given(seatRepository.findAllByIdWithScheduleAndConcert(requestedSeats)).willReturn(List.of(mockSeat));

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }
}