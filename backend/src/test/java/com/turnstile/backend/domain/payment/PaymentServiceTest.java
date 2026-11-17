package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.entity.Payment;
import com.turnstile.backend.domain.payment.entity.PaymentStatus;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 결제 서비스의 비즈니스 로직 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @InjectMocks private PaymentService paymentService;

    @Mock private UserService userService;
    @Mock private ReservationRepository reservationRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUpUserLock() {
        given(userRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(User.builder().id(1L).build()));
    }

    @Test
    @DisplayName("결제 성공 시 모든 검증을 통과하고 PAID 상태와 잔액을 반환해야 한다")
    void should_return_success_response_when_all_validations_pass() {
        // given: Concert Mocking 추가
        String groupId = "success-group";
        Reservation mockReservation = mock(Reservation.class);
        Seat mockSeat = mock(Seat.class);
        ConcertSchedule mockSchedule = mock(ConcertSchedule.class);
        Concert mockConcert = mock(Concert.class); // 추가됨
        User mockUser = mock(User.class);

        given(mockReservation.getUser()).willReturn(mockUser);
        given(mockUser.getId()).willReturn(1L);

        given(mockReservation.getPrice()).willReturn(150000L);
        given(mockReservation.getSeat()).willReturn(mockSeat);
        given(mockSeat.getSchedule()).willReturn(mockSchedule);

        given(mockSchedule.getId()).willReturn(10L);

        given(mockSchedule.getConcert()).willReturn(mockConcert);
        given(mockConcert.getId()).willReturn(100L);

        given(reservationRepository.findAllByGroupIdWithSeat(groupId)).willReturn(List.of(mockReservation));
        given(reservationRepository.confirmReservations(groupId)).willReturn(1);
        given(userService.deductBalance(1L, 150000L)).willReturn(50000L);
        given(seatRepository.confirmSeats(any(), any(), any())).willReturn(1);

        Payment mockPayment = Payment.builder().id(1L).status(PaymentStatus.PAID).paidAt(LocalDateTime.now()).build();
        given(paymentRepository.save(any(Payment.class))).willReturn(mockPayment);

        PaymentRequest request = new PaymentRequest(groupId, 150000L, 100L);

        // when
        PaymentResponse response = paymentService.processPayment(1L, request);

        // then
        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.balance()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("결제 요청 유저와 예약 소유자가 다를 경우 FORBIDDEN_USER 예외가 발생해야 한다")
    void should_throw_exception_when_payment_user_does_not_match_reservation_owner() {
        // given: 예약 소유자는 2L이지만, 결제 요청 유저는 1L
        String groupId = "test-group";
        Reservation mockReservation = mock(Reservation.class);
        User mockUser = mock(User.class);

        given(mockReservation.getUser()).willReturn(mockUser);
        given(mockUser.getId()).willReturn(2L); // 소유자 식별자

        given(reservationRepository.findAllByGroupIdWithSeat(groupId)).willReturn(List.of(mockReservation));

        PaymentRequest request = new PaymentRequest(groupId, 150000L, 100L);

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> paymentService.processPayment(1L, request)); // 1L 유저가 결제 시도

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN_USER);
    }

    @Test
    @DisplayName("예약에 기록된 콘서트 ID와 결제 요청의 콘서트 ID가 다를 경우 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_concert_id_mismatches() {
        // given: 실제 예약된 콘서트는 200L이지만, 다른 콘서트 대기열(100L) 토큰으로 접근 시도
        String groupId = "test-group";
        Reservation mockReservation = mock(Reservation.class);
        Seat mockSeat = mock(Seat.class);
        ConcertSchedule mockSchedule = mock(ConcertSchedule.class);
        Concert mockConcert = mock(Concert.class);
        User mockUser = mock(User.class);

        given(mockReservation.getUser()).willReturn(mockUser);
        given(mockUser.getId()).willReturn(1L);

        given(mockReservation.getSeat()).willReturn(mockSeat);
        given(mockSeat.getSchedule()).willReturn(mockSchedule);
        given(mockSchedule.getConcert()).willReturn(mockConcert);
        given(mockConcert.getId()).willReturn(200L); // 실제 콘서트 ID

        given(reservationRepository.findAllByGroupIdWithSeat(groupId)).willReturn(List.of(mockReservation));

        PaymentRequest request = new PaymentRequest(groupId, 150000L, 100L); // 요청 콘서트 ID 조작

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> paymentService.processPayment(1L, request));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("요청 금액과 실제 예약 금액의 총합이 다를 경우 PAYMENT_AMOUNT_MISMATCH 예외가 발생해야 한다")
    void should_throw_exception_when_amount_mismatches() {
        // given: 금액 검증 단계까지 가기 위해 Concert 연관관계 세팅
        String groupId = "test-group";
        Reservation mockReservation = mock(Reservation.class);
        Seat mockSeat = mock(Seat.class);
        ConcertSchedule mockSchedule = mock(ConcertSchedule.class);
        Concert mockConcert = mock(Concert.class);
        User mockUser = mock(User.class);

        given(mockReservation.getUser()).willReturn(mockUser);
        given(mockUser.getId()).willReturn(1L);

        // 콘서트 교차 검증 통과를 위한 셋업 추가
        given(mockReservation.getSeat()).willReturn(mockSeat);
        given(mockSeat.getSchedule()).willReturn(mockSchedule);
        given(mockSchedule.getConcert()).willReturn(mockConcert);
        given(mockConcert.getId()).willReturn(100L);

        given(mockReservation.getPrice()).willReturn(150000L);

        given(reservationRepository.findAllByGroupIdWithSeat(groupId)).willReturn(List.of(mockReservation));

        PaymentRequest request = new PaymentRequest(groupId, 100000L, 100L);

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> paymentService.processPayment(1L, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("이미 PAID 상태이거나 EXPIRED 상태인 예약 건에 대해 결제 시도 시 ALREADY_PAID 예외가 발생해야 한다")
    void should_throw_exception_when_reservation_is_already_paid_or_expired() {
        // given: 검증을 모두 통과했으나, CAS 업데이트 쿼리 결과가 0
        String groupId = "test-group";
        Reservation mockReservation = mock(Reservation.class);
        Seat mockSeat = mock(Seat.class);
        ConcertSchedule mockSchedule = mock(ConcertSchedule.class);
        Concert mockConcert = mock(Concert.class);
        User mockUser = mock(User.class);

        given(mockReservation.getUser()).willReturn(mockUser);
        given(mockUser.getId()).willReturn(1L);

        given(mockReservation.getPrice()).willReturn(150000L);
        given(mockReservation.getSeat()).willReturn(mockSeat);
        given(mockSeat.getSchedule()).willReturn(mockSchedule);
        given(mockSchedule.getConcert()).willReturn(mockConcert);
        given(mockConcert.getId()).willReturn(100L);

        given(reservationRepository.findAllByGroupIdWithSeat(groupId)).willReturn(List.of(mockReservation));

        // PENDING 상태인 예약이 없어 업데이트된 row 수가 0 반환
        given(reservationRepository.confirmReservations(groupId)).willReturn(0);

        PaymentRequest request = new PaymentRequest(groupId, 150000L, 100L);

        // when & then
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> paymentService.processPayment(1L, request));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_PAID);
    }
}