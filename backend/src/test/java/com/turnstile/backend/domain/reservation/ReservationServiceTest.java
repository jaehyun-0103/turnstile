package com.turnstile.backend.domain.reservation;

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

/**
 * 예약 서비스의 비즈니스 규칙(인가, 수량 제한, 선점 여부) 예외를 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @InjectMocks private ReservationService reservationService;

    @Mock private ReservationRepository reservationRepository;
    @Mock private QueueRedisRepository queueRedisRepository;
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

        given(reservationRepository.existsByUserIdAndStatus(userId, ReservationStatus.PENDING)).willReturn(true);

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

        given(reservationRepository.existsByUserIdAndStatus(userId, ReservationStatus.PENDING)).willReturn(false);
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

        given(reservationRepository.existsByUserIdAndStatus(userId, ReservationStatus.PENDING)).willReturn(false);
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

        given(reservationRepository.existsByUserIdAndStatus(userId, ReservationStatus.PENDING)).willReturn(false);
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

        given(reservationRepository.existsByUserIdAndStatus(userId, ReservationStatus.PENDING)).willReturn(false);
        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId))).willReturn(true);

        // when & then: 하한선 검증 실패로 인한 예외 및 메시지 확인
        TurnstileException exception = assertThrows(TurnstileException.class, () -> reservationService.reserveSeats(userId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThat(exception.getMessage()).contains("최소 1개");
    }
}