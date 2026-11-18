package com.turnstile.backend.domain.payment;

import com.turnstile.backend.domain.concert.entity.*;
import com.turnstile.backend.domain.concert.repository.*;
import com.turnstile.backend.domain.payment.dto.PaymentRequest;
import com.turnstile.backend.domain.payment.dto.PaymentResponse;
import com.turnstile.backend.domain.reservation.dto.ReservationResponse;
import com.turnstile.backend.domain.reservation.entity.ReservationStatus;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.turnstile.backend.support.ReservationPaymentTestSupport;

/**
 * 실제 데이터베이스 환경에서 결제 완료 후 잔액, 좌석, 예약 상태가 원자적으로 변경되는지 검증하는 통합 테스트입니다.
 */
class PaymentIntegrationTest extends ReservationPaymentTestSupport {

    @Test
    @DisplayName("동시 결제 방어: 동일한 예약 그룹에 대해 동시에 10번의 결제 요청이 들어와도 단 1번만 승인되고 잔액도 1회만 차감되어야 한다")
    void should_prevent_double_payment_and_deduct_only_once() throws Exception {
        // given: 4개 좌석을 임시 점유한 상태의 결제 요청 준비
        ReservationResponse reserved = reserve(userA, seatIds);
        PaymentRequest request = paymentRequest(reserved.groupId(), 4000L);

        // when: 10개의 스레드가 동시에 동일한 결제 요청을 시도
        List<Object> results = com.turnstile.backend.support.ConcurrentTestSupport.run(10, index -> {
            try {
                return paymentService.processPayment(userA.getId(), request);
            } catch (TurnstileException e) {
                return e.getErrorCode(); // ALREADY_PAID 등 멱등성 방어 예외
            }
        });

        // then: 정확히 1번만 결제 성공하고 나머지 9번은 ALREADY_PAID로 실패해야 함
        assertThat(results.stream().filter(PaymentResponse.class::isInstance).count()).isEqualTo(1);

        assertThat(results.stream().filter(ErrorCode.class::isInstance).toList()).hasSize(9).allMatch(code -> code == ErrorCode.ALREADY_PAID);

        // then: 결제 이력 1건 생성, 잔액 1회 차감(10,000 - 4,000 = 6,000) 및 좌석·예약 상태 전이 검증
        assertThat(payments.count()).isEqualTo(1);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(6000L);

        assertThat(seats.findAll()).allMatch(s -> s.getStatus() == SeatStatus.SOLD);

        assertThat(reservations.findAll()).allMatch(r -> r.getStatus() == ReservationStatus.PAID);
    }

    @Test
    @DisplayName("잔액이 부족한 경우 INSUFFICIENT_BALANCE 예외가 발생하고 모든 예약 및 좌석 상태 변경이 롤백되어야 한다")
    void should_rollback_when_balance_is_insufficient() {
        // given: 2개 좌석을 임시 점유한 뒤 사용자 잔액을 0원으로 변경
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        jdbc.update("UPDATE users SET balance = 0 WHERE id = ?", userA.getId());

        // when & then: 결제 실패 시 INSUFFICIENT_BALANCE 예외 발생 및 전체 롤백 검증
        assertPaymentRollback(reserved, ErrorCode.INSUFFICIENT_BALANCE, 0L);
    }

    @Test
    @DisplayName("점유한 좌석 중 하나라도 만료 시간이 지난 경우 INVALID_SEAT_HOLD 예외와 함께 전체 결제가 롤백되어야 한다")
    void should_rollback_all_changes_when_one_seat_is_expired() {
        // given: 2개 좌석을 임시 점유한 뒤 첫 번째 좌석의 점유 만료 시각을 과거로 조작
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        jdbc.update(
                "UPDATE seat SET hold_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                seatIds.getFirst()
        );

        // when & then: 결제 실패 시 INVALID_SEAT_HOLD 예외 발생 및 전체 롤백 검증
        assertPaymentRollback(reserved, ErrorCode.INVALID_SEAT_HOLD, 10000L);
    }

    @Test
    @DisplayName("점유한 좌석 중 하나라도 타인에게 점유된 경우 INVALID_SEAT_HOLD 예외와 함께 전체 결제가 롤백되어야 한다")
    void should_rollback_all_changes_when_one_seat_is_held_by_another_user() {
        // given: 2개 좌석을 임시 점유한 뒤 첫 번째 좌석의 점유자를 다른 사용자로 변경
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        jdbc.update(
                "UPDATE seat SET hold_user_id = ? WHERE id = ?",
                userB.getId(),
                seatIds.getFirst()
        );

        // when & then: 결제 실패 시 INVALID_SEAT_HOLD 예외 발생 및 전체 롤백 검증
        assertPaymentRollback(reserved, ErrorCode.INVALID_SEAT_HOLD, 10000L);
    }

    @Test
    @DisplayName("결제 성공 시 유저 잔액이 차감되고 좌석은 SOLD, 예약은 PAID 상태로 함께 커밋되어야 한다")
    void should_complete_payment_flow_atomically() {
        // given: 2개 좌석을 임시 점유한 상태 준비
        ReservationResponse reserved = reserve(userA, seatIds.subList(0, 2));

        // when: 결제 프로세스 실행
        PaymentResponse result = paymentService.processPayment(userA.getId(), paymentRequest(reserved.groupId(), 2000L));

        // then: 잔액 차감 검증 (10,000 - 2,000 = 8,000)
        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.balance()).isEqualTo(8000L);
        assertThat(users.findById(userA.getId()).orElseThrow().getBalance()).isEqualTo(8000L);

        // then: 결제 이력 생성 및 예약 PAID, 좌석 SOLD 상태 전이 검증
        assertThat(payments.count()).isEqualTo(1);
        assertThat(reservations.findAll()).hasSize(2).allMatch(r -> r.getStatus() == ReservationStatus.PAID);
        assertThat(seats.findAllById(seatIds.subList(0, 2))).allMatch(seat -> seat.getStatus() == SeatStatus.SOLD);
    }
}