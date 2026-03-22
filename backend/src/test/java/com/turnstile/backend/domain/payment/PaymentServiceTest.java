package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.entity.ConcertSchedule;
import com.turnstile.backend.domain.concert.entity.Seat;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.payment.entity.Payment;
import com.turnstile.backend.domain.payment.entity.PaymentStatus;
import com.turnstile.backend.domain.payment.repository.PaymentRepository;
import com.turnstile.backend.domain.payment.service.PaymentService;
import com.turnstile.backend.domain.reservation.entity.Reservation;
import com.turnstile.backend.domain.reservation.repository.ReservationRepository;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.service.UserService;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.global.common.CacheEvictor;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

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
    @Mock private PaymentRepository paymentRepository;
    @Mock private CacheEvictor cacheEvictor;

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
        given(mockSchedule.getConcert()).willReturn(mockConcert);
        given(mockConcert.getId()).willReturn(100L);

        given(reservationRepository.findAllByGroupIdWithSeat(groupId)).willReturn(List.of(mockReservation));
        given(reservationRepository.confirmReservations(groupId)).willReturn(1);
        given(userService.deductBalance(1L, 150000L)).willReturn(50000L);

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
    @DisplayName("요청 금액과 실제 예약 금액의 총합이 다를 경우 PAYMENT_AMOUNT_MISMATCH 예외가 발생해야 한다")
    void should_throw_exception_when_amount_mismatches() {
        // given: 금액 검증 단계까지 살아서 가기 위해 Concert 연관관계 세팅
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
}